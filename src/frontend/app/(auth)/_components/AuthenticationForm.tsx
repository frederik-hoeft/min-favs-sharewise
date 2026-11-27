"use client";

import { Alert, Button, Paper, PasswordInput, Stack, Text, TextInput, Title } from "@mantine/core";
import { WarningCircleIcon } from "@phosphor-icons/react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { ChangeEvent, Dispatch, FormEvent, ReactElement, RefObject, SetStateAction } from "react";
import { useExclusiveOperation, type ExclusiveOperation, type OperationOutcome } from "@/lib/hooks/use-exclusive-operation";
import { ApiError } from "@/lib/api";
import { safeAppReturnPath } from "@/lib/navigation/return-path";
import { INVITE_PATH, INVITE_RETURN_QUERY } from "@/lib/navigation/invitation-routes";
import { useInvitationContinuation } from "@/app/invitation-continuation";
import type { IdentityRuntime } from "@/core/identity";
import { useIdentity } from "@/app/identity-provider";
import { validateDisplayName, MAX_DISPLAY_NAME_CHARACTERS } from "@/core/identity/display-name";
import { validatePasswordConfirmation } from "@/core/identity/password-validation";
import { suggestDisplayName, validateCredentials, type CredentialErrors } from "./credentials-validation";
import styles from "./AuthenticationForm.module.scss";

type Mode = "login" | "register";

function mapFailure(error: unknown, mode: Mode): { fields: CredentialErrors; message?: string } {
  if (!(error instanceof ApiError)) return { fields: {}, message: 'Something went wrong. Please try again.' };
  // Login denial must never reveal account existence, lockout or disabled status.
  if (mode === 'login' && error.status === 401) {
    return { fields: {}, message: "We couldn't sign you in. Check your email and password and try again." };
  }
  if (mode === 'register' && error.status === 409 && error.code === 'email_unavailable') {
    return { fields: { email: 'This email address is unavailable.' } };
  }
  if (error.status === 400) {
    const fields: { email?: string; password?: string; displayName?: string } = {};
    for (const issue of error.fieldErrors) {
      if ((issue.field === 'email' || issue.field === 'password' || (mode === 'register' && issue.field === 'displayName')) && !fields[issue.field]) {
        fields[issue.field] = issue.message || 'Please check this field.';
      }
    }
    return { fields, message: Object.keys(fields).length ? undefined : 'Check the information you entered and try again.' };
  }
  // Avoid exposing network exception messages or sensitive server diagnostics.
  if (error.code === 'auth_lock_unavailable') {
    return { fields: {}, message: 'Secure sign-in is unavailable in this browser. Please use a browser with Web Locks support.' };
  }
  if (error.code === 'auth_in_progress') return { fields: {}, message: 'A sign-in request is already in progress.' };
  if (mode === 'register' && error.code === 'registration_outcome_unknown') {
    return { fields: {}, message: 'We could not confirm whether your account was created. Try signing in with these credentials first; if that fails, you can retry registration.' };
  }
  if (error.status === 0) return { fields: {}, message: 'Unable to complete authentication. Check your connection and try again.' };
  return { fields: {}, message: 'Unable to complete authentication right now. Please try again.' };
}

export function AuthenticationForm({ mode }: { mode: Mode }): ReactElement {
  const isRegistration: boolean = mode === 'register';
  const router: ReturnType<typeof useRouter> = useRouter();
  const { auth }: Pick<IdentityRuntime, "auth" | "session"> = useIdentity();
  const { pending: invitationPending, continuation }: ReturnType<typeof useInvitationContinuation> = useInvitationContinuation();
  const invitationDetour: boolean = invitationPending && typeof window !== "undefined" && new URLSearchParams(window.location.search).get("next") === INVITE_PATH;
  const [email, setEmail]: [string, Dispatch<SetStateAction<string>>] = useState('');
  const [password, setPassword]: [string, Dispatch<SetStateAction<string>>] = useState('');
  // Undefined means automatic suggestion; an explicit empty edit is still an edit.
  const [displayNameOverride, setDisplayNameOverride]: [string | undefined, Dispatch<SetStateAction<string | undefined>>] = useState<string>();
  const displayName: string = displayNameOverride ?? (isRegistration ? suggestDisplayName(email) : '');
  const [confirmPassword, setConfirmPassword]: [string, Dispatch<SetStateAction<string>>] = useState('');
  const [errors, setErrors]: [CredentialErrors, Dispatch<SetStateAction<CredentialErrors>>] = useState<CredentialErrors>({});
  const [message, setMessage]: [string | undefined, Dispatch<SetStateAction<string | undefined>>] = useState<string>();
  const operation: ExclusiveOperation<"authenticate"> = useExclusiveOperation<"authenticate">();
  const pending: boolean = operation.pending !== null;
  const emailRef: RefObject<HTMLInputElement | null> = useRef<HTMLInputElement>(null);
  const displayNameRef: RefObject<HTMLInputElement | null> = useRef<HTMLInputElement>(null);
  const confirmRef: RefObject<HTMLInputElement | null> = useRef<HTMLInputElement>(null);
  const passwordRef: RefObject<HTMLInputElement | null> = useRef<HTMLInputElement>(null);
  const alertRef: RefObject<HTMLDivElement | null> = useRef<HTMLDivElement>(null);
  const deferredFocus: RefObject<CredentialErrors | undefined> = useRef<CredentialErrors | undefined>(undefined);

  useEffect(() => {
    if (message && !pending) alertRef.current?.focus();
  }, [message, pending]);

  // Async field errors arrive while inputs are disabled. Focus once after enabling,
  // never on every keystroke while the user edits an invalid form.
  useEffect(() => {
    if (!pending && deferredFocus.current) {
      focusFirstError(deferredFocus.current);
      deferredFocus.current = undefined;
    }
  }, [pending]);

  function focusFirstError(fields: CredentialErrors) {
    if (fields.email) emailRef.current?.focus();
    else if (fields.displayName) displayNameRef.current?.focus();
    else if (fields.password) passwordRef.current?.focus();
    else if (fields.confirmPassword) confirmRef.current?.focus();
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (operation.isRunning()) return;
    const trimmedEmail: string = email.trim();
    setEmail(trimmedEmail);
    const validation: CredentialErrors = { ...validateCredentials(mode, trimmedEmail, password),
      ...(isRegistration ? { displayName: validateDisplayName(displayName), confirmPassword: validatePasswordConfirmation(password, confirmPassword) } : {}) };
    setErrors(validation);
    setMessage(undefined);
    if (validation.email || validation.password || validation.displayName || validation.confirmPassword) {
      focusFirstError(validation);
      return;
    }
    const path: string = window.location.pathname + window.location.search;
    const candidate: string | null = new URLSearchParams(window.location.search).get('next');
    operation.start("authenticate", (): Promise<void> => isRegistration ? auth.register({ email: trimmedEmail, password, displayName }) : auth.login({ email: trimmedEmail, password }), (outcome: OperationOutcome<void>): void => {
      // Authentication itself may have committed, but an abandoned form cannot direct navigation.
      if (window.location.pathname + window.location.search !== path) return;
      if (outcome.status === "fulfilled") {
        const next: string = candidate === INVITE_PATH && continuation?.pending ? INVITE_PATH
          : isRegistration ? '/app' : safeAppReturnPath(candidate);
        router.replace(next);
        return;
      }
      const failure: ReturnType<typeof mapFailure> = mapFailure(outcome.error, mode);
      setErrors(failure.fields);
      setMessage(failure.message);
      if (failure.fields.email || failure.fields.password || failure.fields.displayName) deferredFocus.current = failure.fields;
    });
  }

  return (
    <div className={styles.container}>
      <Paper className={styles.card} radius="xl" withBorder>
        <Stack gap="xl">
          <div className={styles.intro}>
            <Text className={styles.eyebrow} size="xs" fw={700} tt="uppercase">Your shared space</Text>
            <Title order={1}>{isRegistration ? 'Start sharing clearly.' : 'Welcome back.'}</Title>
            <Text c="dimmed" size="sm">
              {isRegistration ? 'Create an account to get started with ShareWise.' : 'Sign in to your ShareWise account.'}
            </Text>
          </div>
          <form onSubmit={submit} noValidate aria-label={isRegistration ? 'Create account' : 'Sign in'}>
            <Stack gap="md">
              {message && (
                <div ref={alertRef} tabIndex={-1} className={styles.errorFocus}>
                  <Alert color="red" title="Authentication unsuccessful" icon={<WarningCircleIcon aria-hidden="true" />} role="alert">
                    {message}
                  </Alert>
                </div>
              )}
              <TextInput
                ref={emailRef}
                label="Email address"
                type="email"
                name="email"
                autoComplete="email"
                inputMode="email"
                required
                value={email}
                error={errors.email}
                onChange={(event: ChangeEvent<HTMLInputElement>): void => {
                  const nextEmail: string = event.currentTarget.value;
                  const suggestionChanged: boolean = isRegistration && displayNameOverride === undefined && suggestDisplayName(nextEmail) !== displayName;
                  setEmail(nextEmail);
                  setErrors((current: CredentialErrors): CredentialErrors => ({ ...current, email: undefined,
                    ...(suggestionChanged ? { displayName: undefined } : {}) }));
                  setMessage(undefined);
                }}
                disabled={pending}
              />
              {isRegistration && <TextInput ref={displayNameRef} label="Display name" name="displayName"
                autoComplete="nickname" required maxLength={MAX_DISPLAY_NAME_CHARACTERS * 2}
                description="Suggested from your email. You can change this name, which is visible to signed-in ShareWise users." value={displayName} error={errors.displayName}
                disabled={pending} onChange={(event: ChangeEvent<HTMLInputElement>): void => {
                  setDisplayNameOverride(event.currentTarget.value); setErrors((current: CredentialErrors): CredentialErrors => ({ ...current, displayName: undefined })); setMessage(undefined);
                }} />}
              <PasswordInput
                ref={passwordRef}
                label="Password"
                name="password"
                autoComplete={isRegistration ? 'new-password' : 'current-password'}
                required
                value={password}
                error={errors.password}
                onChange={(event: ChangeEvent<HTMLInputElement>): void => { setPassword(event.currentTarget.value); setErrors((current: CredentialErrors): CredentialErrors => ({ ...current, password: undefined, confirmPassword: undefined })); setMessage(undefined); }}
                disabled={pending}
                description={isRegistration ? 'Use between 8 and 256 characters.' : undefined}
              />
              {isRegistration && <PasswordInput ref={confirmRef} label="Confirm password" name="confirmPassword"
                autoComplete="new-password" required maxLength={256} value={confirmPassword} error={errors.confirmPassword}
                disabled={pending} onChange={(event: ChangeEvent<HTMLInputElement>): void => {
                  setConfirmPassword(event.currentTarget.value); setErrors((current: CredentialErrors): CredentialErrors => ({ ...current, confirmPassword: undefined })); setMessage(undefined);
                }} />}
              <Button type="submit" size="md" fullWidth loading={pending} disabled={pending} className={styles.submit}>
                {isRegistration ? 'Create account' : 'Sign in'}
              </Button>
            </Stack>
          </form>
          <Text size="sm" ta="center" c="dimmed">
            {isRegistration ? 'Already have an account?' : 'New to ShareWise?'}{' '}
            <Link href={`${isRegistration ? '/auth/login' : '/auth/register'}${invitationDetour ? INVITE_RETURN_QUERY : ''}`} className={styles.textLink}>
              {isRegistration ? 'Sign in' : 'Create an account'}
            </Link>
          </Text>
        </Stack>
      </Paper>
      <Text ta="center" size="sm" c="dimmed" className={styles.secondary}>
        Want to explore first? <Link href="/#product" className={styles.textLink}>Learn more about ShareWise</Link>
      </Text>
    </div>
  );
}

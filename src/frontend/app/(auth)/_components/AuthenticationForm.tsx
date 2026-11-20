"use client";

import { Alert, Button, Paper, PasswordInput, Stack, Text, TextInput, Title } from "@mantine/core";
import { WarningCircleIcon } from "@phosphor-icons/react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ApiError } from "@/lib/api";
import { useIdentity } from "@/app/identity-provider";
import { validateCredentials, type CredentialErrors } from "./credentials-validation";
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
    const fields: { email?: string; password?: string } = {};
    for (const issue of error.fieldErrors) {
      if ((issue.field === 'email' || issue.field === 'password') && !fields[issue.field]) {
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
  if (error.status === 0) return { fields: {}, message: 'Unable to complete authentication. Check your connection and try again.' };
  return { fields: {}, message: 'Unable to complete authentication right now. Please try again.' };
}

export function AuthenticationForm({ mode }: { mode: Mode }) {
  const isRegistration = mode === 'register';
  const router = useRouter();
  const { auth } = useIdentity();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<CredentialErrors>({});
  const [message, setMessage] = useState<string>();
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const alertRef = useRef<HTMLDivElement>(null);
  const deferredFocus = useRef<CredentialErrors | undefined>(undefined);

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
    else if (fields.password) passwordRef.current?.focus();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    const trimmedEmail = email.trim();
    setEmail(trimmedEmail);
    const validation = validateCredentials(mode, trimmedEmail, password);
    setErrors(validation);
    setMessage(undefined);
    if (validation.email || validation.password) {
      focusFirstError(validation);
      return;
    }
    submitting.current = true;
    setPending(true);
    try {
      await auth[mode]({ email: trimmedEmail, password });
      // Client-side navigation retains the root identity runtime; a full reload intentionally does not.
      router.replace('/app');
    } catch (error) {
      const failure = mapFailure(error, mode);
      setErrors(failure.fields);
      setMessage(failure.message);
      if (failure.fields.email || failure.fields.password) deferredFocus.current = failure.fields;
    } finally {
      submitting.current = false;
      setPending(false);
    }
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
                onChange={(event) => { setEmail(event.currentTarget.value); setErrors((current) => ({ ...current, email: undefined })); setMessage(undefined); }}
                disabled={pending}
              />
              <PasswordInput
                ref={passwordRef}
                label="Password"
                name="password"
                autoComplete={isRegistration ? 'new-password' : 'current-password'}
                required
                value={password}
                error={errors.password}
                onChange={(event) => { setPassword(event.currentTarget.value); setErrors((current) => ({ ...current, password: undefined })); setMessage(undefined); }}
                disabled={pending}
                description={isRegistration ? 'Use at least 12 characters.' : undefined}
              />
              <Button type="submit" size="md" fullWidth loading={pending} disabled={pending} className={styles.submit}>
                {isRegistration ? 'Create account' : 'Sign in'}
              </Button>
            </Stack>
          </form>
          <Text size="sm" ta="center" c="dimmed">
            {isRegistration ? 'Already have an account?' : 'New to ShareWise?'}{' '}
            <Link href={isRegistration ? '/auth/login' : '/auth/register'} className={styles.textLink}>
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

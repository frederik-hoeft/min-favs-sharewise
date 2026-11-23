"use client";

import { Alert, Button, Card, Group, PasswordInput, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useIdentity } from "@/app/identity-provider";
import { validatePasswordChange } from "@/core/identity/password-validation";
import type { PasswordChangeErrors } from "@/core/identity/password-validation";
import { ApiError } from "@/lib/api";

type Notice = {
  readonly kind: "success" | "error" | "uncertain";
  readonly message: string;
  readonly blocked?: boolean;
  readonly signIn?: boolean;
};

type Failure = { readonly notice: Notice; readonly fields: PasswordChangeErrors };

function failureFor(error: unknown): Failure {
  const fields: { currentPassword?: string; newPassword?: string } = {};
  if (error instanceof ApiError) {
    if (error.status === 400 && error.code === "validation_failed") {
      for (const issue of error.fieldErrors) {
        if ((issue.field === "currentPassword" || issue.field === "newPassword") && !fields[issue.field]) {
          fields[issue.field] = issue.message || "Check this password.";
        }
      }
      return { fields, notice: { kind: "error", message: "Check the password fields and try again." } };
    }
    if (error.status === 400 && error.code === "current_password_invalid") {
      return { fields: { currentPassword: "Check your current password." }, notice: {
        kind: "error", message: "The current password is incorrect, or your account is temporarily locked.",
      } };
    }
    if (error.status === 409 && error.code === "credential_changed") {
      return { fields, notice: { kind: "error", blocked: true, signIn: true,
        message: "Your credentials changed during this request. Sign in again before changing your password." } };
    }
    if (error.status === 403 && error.code === "account_unavailable") {
      return { fields, notice: { kind: "error", blocked: true,
        message: "This account is unavailable. Contact support if you need assistance." } };
    }
    if (error.status === 401) {
      return { fields, notice: { kind: "error", blocked: true, signIn: true,
        message: "Your session is no longer available. Sign in again to continue." } };
    }
    if (error.status === 400 && error.code === "malformed_request") {
      return { fields, notice: { kind: "error", message: "The request was rejected. Check your entries and try again." } };
    }
  }
  // Any unrecognized or lost response may hide a committed credential mutation. Never automatically resubmit.
  return { fields, notice: { kind: "uncertain", blocked: true,
    message: "We couldn't confirm whether your password changed. Do not submit the change again yet. Reload to recover your session, or sign in with the new password to verify the result." } };
}

export function AccountPasswordForm() {
  const { security } = useIdentity();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [errors, setErrors] = useState<PasswordChangeErrors>({});
  const [notice, setNotice] = useState<Notice>();
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const currentRef = useRef<HTMLInputElement>(null);
  const newRef = useRef<HTMLInputElement>(null);
  const alertRef = useRef<HTMLDivElement>(null);
  const deferredFocus = useRef<PasswordChangeErrors | undefined>(undefined);

  function focusFirst(fields: PasswordChangeErrors) {
    if (fields.currentPassword) currentRef.current?.focus();
    else if (fields.newPassword) newRef.current?.focus();
  }

  useEffect(() => {
    if (!pending && deferredFocus.current) {
      focusFirst(deferredFocus.current);
      deferredFocus.current = undefined;
    }
  }, [pending]);

  useEffect(() => {
    if (!pending && notice && (notice.kind === "uncertain" || !errors.currentPassword && !errors.newPassword)) {
      alertRef.current?.focus();
    }
  }, [pending, notice, errors]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || notice?.blocked) return;
    const validation = validatePasswordChange(currentPassword, newPassword);
    setErrors(validation);
    setNotice(undefined);
    if (validation.currentPassword || validation.newPassword) {
      focusFirst(validation);
      return;
    }

    submitting.current = true;
    setPending(true);
    try {
      await security.changePassword({ currentPassword, newPassword });
      setCurrentPassword("");
      setNewPassword("");
      setErrors({});
      setNotice({ kind: "success", message: "Password changed. This session stays signed in. Other sessions may remain active until their current access tokens expire; they cannot refresh and must then sign in again." });
    } catch (error) {
      const failure = failureFor(error);
      setErrors(failure.fields);
      setNotice(failure.notice);
      if (failure.fields.currentPassword || failure.fields.newPassword) deferredFocus.current = failure.fields;
      if (failure.notice.blocked) {
        setCurrentPassword("");
        setNewPassword("");
      }
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }

  const blocked = Boolean(notice?.blocked);
  return (
    <Card withBorder padding="xl" radius="lg">
      <Stack gap="lg">
        <Stack gap={4}>
          <Title order={2} size="h3">Security</Title>
          <Text size="sm" c="dimmed">Enter your current password and choose a new one (8 to 256 characters). Other sessions will need to sign in again after their existing access tokens expire.</Text>
        </Stack>
        {notice && (
          <Alert ref={alertRef} tabIndex={-1}
            color={notice.kind === "success" ? "indigo" : "red"}
            role={notice.kind === "success" ? "status" : "alert"}
            title={notice.kind === "success" ? "Password updated" :
              notice.kind === "uncertain" ? "Password change unconfirmed" : "Password change unsuccessful"}>
            <Stack gap="sm" align="flex-start">
              <Text size="sm">{notice.message}</Text>
              {notice.kind === "uncertain" && <Button component="a" href="/app/account" variant="light">Reload account</Button>}
              {notice.signIn && <Button component={Link} href="/auth/login" variant="light">Sign in</Button>}
              {notice.kind === "uncertain" && <Button component={Link} href="/auth/login" variant="subtle">Sign in instead</Button>}
            </Stack>
          </Alert>
        )}
        <form aria-label="Change password" onSubmit={(event) => { void submit(event); }} noValidate>
          <Stack gap="md">
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              <PasswordInput ref={currentRef} name="currentPassword" label="Current password"
                value={currentPassword} maxLength={256} autoComplete="current-password"
                disabled={pending || blocked} error={errors.currentPassword}
                onChange={(event) => {
                  setCurrentPassword(event.currentTarget.value);
                  setErrors((previous) => ({ ...previous, currentPassword: undefined }));
                  if (!blocked) setNotice(undefined);
                }} />
              <PasswordInput ref={newRef} name="newPassword" label="New password"
                value={newPassword} maxLength={256} autoComplete="new-password"
                disabled={pending || blocked} error={errors.newPassword}
                onChange={(event) => {
                  setNewPassword(event.currentTarget.value);
                  setErrors((previous) => ({ ...previous, newPassword: undefined }));
                  if (!blocked) setNotice(undefined);
                }} />
            </SimpleGrid>
            <Group justify="flex-end">
              <Button type="submit" loading={pending} disabled={pending || blocked}>Change password</Button>
            </Group>
          </Stack>
        </form>
      </Stack>
    </Card>
  );
}

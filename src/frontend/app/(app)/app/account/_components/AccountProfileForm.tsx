"use client";

import { Alert, Button, Card, Group, Loader, SimpleGrid, Stack, Text, TextInput, Title } from "@mantine/core";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useAuthState, useIdentity } from "@/app/identity-provider";
import type { AccountProfile, AuthState } from "@/core/identity";
import { MAX_DISPLAY_NAME_CHARACTERS, validateDisplayName, withinDisplayNameLimit } from "@/core/identity/display-name";
import { ApiError } from "@/lib/api";

type ProfileView =
  | { readonly context: AuthState; readonly attempt: number; readonly status: "loading" }
  | { readonly context: AuthState; readonly attempt: number; readonly status: "ready"; readonly profile: AccountProfile }
  | { readonly context: AuthState; readonly attempt: number; readonly status: "error"; readonly message: string }
  | { readonly context: AuthState; readonly attempt: number; readonly status: "unavailable" };

type Feedback = {
  readonly context: AuthState;
  readonly kind: "success" | "error";
  readonly message: string;
  readonly reloadRequired?: boolean;
};

function loadFailure(error: unknown): string {
  if (error instanceof ApiError && error.status === 0) {
    return "Unable to load your account. Check your connection and try again.";
  }
  return "Unable to load your account right now. Please try again.";
}

function updateFailure(error: unknown): { message: string; field?: string; reloadRequired?: boolean } {
  if (error instanceof ApiError) {
    if (error.status === 400 && error.code === "validation_failed") {
      const field = error.fieldErrors.find((issue) => issue.field === "displayName");
      if (field) return { message: "Check your display name.", field: field.message };
    }
    if (error.status === 400) return { message: "The display name was rejected. Check your entry and try again." };
    // A lost mutation response or server failure does not establish whether IAM committed the write.
    if (error.status === 0 || error.status >= 500) {
      return { message: "We could not confirm the update. Reload your account before making another change.", reloadRequired: true };
    }
  }
  return { message: "Unable to update your account right now. Please try again." };
}

/** Data belongs to the active bearer context, not a pathname, account ID or browser storage. */
export function AccountProfileForm() {
  const authState = useAuthState();
  const { account, session } = useIdentity();
  const [view, setView] = useState<ProfileView>();
  const [draft, setDraft] = useState("");
  const [fieldError, setFieldError] = useState<string>();
  const [feedback, setFeedback] = useState<Feedback>();
  const [reloadCount, setReloadCount] = useState(0);
  const [pendingContext, setPendingContext] = useState<AuthState>();
  const inFlight = useRef<AuthState | undefined>(undefined);
  const inputRef = useRef<HTMLInputElement>(null);
  const focusWhenEnabled = useRef(false);
  const pending = pendingContext === authState;

  // A server field error can arrive while the input is disabled for the mutation.
  useEffect(() => {
    if (!pending && focusWhenEnabled.current) {
      inputRef.current?.focus();
      focusWhenEnabled.current = false;
    }
  }, [pending]);

  useEffect(() => {
    if (authState.status !== "authenticated") return;

    const context = authState;
    const controller = new AbortController();
    let active = true;

    void account.getAccount({ signal: controller.signal }).then((profile) => {
      if (!active || session.getSnapshot() !== context) return;
      setView({ context, attempt: reloadCount, status: "ready", profile });
      setDraft(profile.displayName ?? "");
      setFieldError(undefined);
      setFeedback(undefined);
    }).catch((error: unknown) => {
      if (!active || session.getSnapshot() !== context) return;
      if (error instanceof ApiError && error.status === 403 && error.code === "account_unavailable") {
        setView({ context, attempt: reloadCount, status: "unavailable" });
      } else {
        setView({ context, attempt: reloadCount, status: "error", message: loadFailure(error) });
      }
    });

    return () => {
      active = false;
      controller.abort();
    };
  }, [account, authState, reloadCount, session]);

  const currentView = view?.context === authState && view.attempt === reloadCount ? view : undefined;
  const currentFeedback = feedback?.context === authState ? feedback : undefined;
  // An empty or whitespace-only entry clears the optional name; otherwise preserve it exactly.
  const nextDisplayName = draft.trim().length === 0 ? null : draft;

  async function mutate() {
    if (currentView?.status !== "ready" || session.getSnapshot() !== authState ||
        inFlight.current === authState || currentFeedback?.reloadRequired || fieldError ||
        nextDisplayName === currentView.profile.displayName) return;

    if (nextDisplayName !== null) {
      const error = validateDisplayName(nextDisplayName);
      setFieldError(error);
      if (error) {
        inputRef.current?.focus();
        return;
      }
    }
    const context = authState;
    inFlight.current = context;
    setPendingContext(context);
    setFieldError(undefined);
    setFeedback(undefined);
    try {
      const profile = nextDisplayName === null ? await account.clearDisplayName() : await account.setDisplayName(nextDisplayName);
      if (session.getSnapshot() !== context) return;
      setView({ context, attempt: reloadCount, status: "ready", profile });
      setDraft(profile.displayName ?? "");
      setFeedback({ context, kind: "success", message: nextDisplayName === null ? "Display name cleared." : "Display name updated." });
    } catch (error) {
      if (session.getSnapshot() !== context) return;
      if (error instanceof ApiError && error.status === 403 && error.code === "account_unavailable") {
        setView({ context, attempt: reloadCount, status: "unavailable" });
        return;
      }
      const failure = updateFailure(error);
      setFieldError(failure.field);
      setFeedback({ context, kind: "error", message: failure.message, reloadRequired: failure.reloadRequired });
      if (failure.field) focusWhenEnabled.current = true;
    } finally {
      if (inFlight.current === context) inFlight.current = undefined;
      setPendingContext((current) => current === context ? undefined : current);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void mutate();
  }

  // A route-level guard owns sign-in navigation; do not render stale profile data.
  if (authState.status !== "authenticated") return null;

  if (!currentView || currentView.status === "loading") {
    return <Group role="status" aria-label="Loading account" gap="sm"><Loader size="sm" /><Text>Loading account details…</Text></Group>;
  }

  if (currentView.status === "unavailable") {
    return <Alert color="red" role="alert" title="Account unavailable">This account is unavailable. Please contact support if you need assistance.</Alert>;
  }

  if (currentView.status === "error") {
    return (
      <Alert color="red" role="alert" title="Unable to load account">
        <Stack gap="sm" align="flex-start">
          <Text size="sm">{currentView.message}</Text>
          <Button variant="light" onClick={() => setReloadCount((count) => count + 1)}>Retry loading</Button>
        </Stack>
      </Alert>
    );
  }

  const profile = currentView.profile;
  const unchanged = nextDisplayName === profile.displayName;
  return (
    <Card withBorder padding="xl" radius="lg">
      <Stack gap="lg">
        <Stack gap={4}>
          <Title order={2} size="h3">Profile</Title>
          <Text size="sm" c="dimmed">Your email is read-only. Leave the display name blank to remove it.</Text>
        </Stack>
        {currentFeedback && (
          <Alert color={currentFeedback.kind === "success" ? "indigo" : "red"}
            role={currentFeedback.kind === "success" ? "status" : "alert"}
            title={currentFeedback.kind === "success" ? "Profile updated" : "Update unsuccessful"}>
            <Stack gap="sm" align="flex-start">
              <Text size="sm">{currentFeedback.message}</Text>
              {currentFeedback.reloadRequired && (
                <Button variant="light" onClick={() => setReloadCount((count) => count + 1)}>Reload account</Button>
              )}
            </Stack>
          </Alert>
        )}
        <form aria-label="Account profile" onSubmit={submit} noValidate>
          <Stack gap="md">
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              <TextInput label="Email address" value={profile.email} readOnly autoComplete="email" />
              <TextInput
                ref={inputRef}
                label="Display name"
                name="displayName"
                placeholder="No display name"
                // A safe native UTF-16 bound plus a scalar-aware guard preserves all 80-character Unicode names.
                maxLength={MAX_DISPLAY_NAME_CHARACTERS * 2}
                value={draft}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  if (!withinDisplayNameLimit(value)) {
                    setFieldError("Enter at most 80 characters.");
                    setFeedback(undefined);
                    return;
                  }
                  setDraft(value);
                  setFieldError(undefined);
                  setFeedback(undefined);
                }}
                error={fieldError}
                disabled={pending || currentFeedback?.reloadRequired}
                autoComplete="nickname"
              />
            </SimpleGrid>
            <Group justify="flex-end">
              <Button type="submit" loading={pending}
                disabled={pending || Boolean(currentFeedback?.reloadRequired) || Boolean(fieldError) || unchanged}>Update display name</Button>
            </Group>
          </Stack>
        </form>
      </Stack>
    </Card>
  );
}

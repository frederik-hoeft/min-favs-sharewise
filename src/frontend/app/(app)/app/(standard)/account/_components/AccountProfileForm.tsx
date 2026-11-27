"use client";

import { Alert, Button, Card, Group, Loader, SimpleGrid, Stack, Text, TextInput, Title } from "@mantine/core";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useAuthenticatedApis } from "@/app/identity-provider";
import type { AccountProfile } from "@/core/identity";
import { MAX_DISPLAY_NAME_CHARACTERS, validateDisplayName, withinDisplayNameLimit } from "@/core/identity/display-name";
import { ApiError } from "@/lib/api";

type ProfileView =
  | { readonly attempt: number; readonly status: "loading" }
  | { readonly attempt: number; readonly status: "ready"; readonly profile: AccountProfile }
  | { readonly attempt: number; readonly status: "error"; readonly message: string }
  | { readonly attempt: number; readonly status: "unavailable" };

type Feedback = {
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

export function AccountProfileForm() {
  const { account } = useAuthenticatedApis();
  const [view, setView] = useState<ProfileView>();
  const [draft, setDraft] = useState("");
  const [fieldError, setFieldError] = useState<string>();
  const [feedback, setFeedback] = useState<Feedback>();
  const [reloadCount, setReloadCount] = useState(0);
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const focusWhenEnabled = useRef(false);

  // A server field error can arrive while the input is disabled for the mutation.
  useEffect(() => {
    if (!pending && focusWhenEnabled.current) {
      inputRef.current?.focus();
      focusWhenEnabled.current = false;
    }
  }, [pending]);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;

    void account.getAccount({ signal: controller.signal }).then((profile) => {
      if (!active) return;
      setView({ attempt: reloadCount, status: "ready", profile });
      setDraft(profile.displayName);
      setFieldError(undefined);
      setFeedback(undefined);
    }).catch((error: unknown) => {
      if (!active) return;
      if (error instanceof ApiError && error.status === 403 && error.code === "account_unavailable") {
        setView({ attempt: reloadCount, status: "unavailable" });
      } else {
        setView({ attempt: reloadCount, status: "error", message: loadFailure(error) });
      }
    });

    return () => {
      active = false;
      controller.abort();
    };
  }, [account, reloadCount]);

  const currentView = view?.attempt === reloadCount ? view : undefined;
  const nextDisplayName = draft;

  async function mutate() {
    if (currentView?.status !== "ready" || inFlight.current || feedback?.reloadRequired || fieldError ||
        nextDisplayName === currentView.profile.displayName) return;

    const error = validateDisplayName(nextDisplayName);
    setFieldError(error);
    if (error) {
      inputRef.current?.focus();
      return;
    }
    inFlight.current = true;
    setPending(true);
    setFieldError(undefined);
    setFeedback(undefined);
    try {
      const profile = await account.setDisplayName(nextDisplayName);
      setView({ attempt: reloadCount, status: "ready", profile });
      setDraft(profile.displayName);
      setFeedback({ kind: "success", message: "Display name updated." });
    } catch (error) {
      if (error instanceof ApiError && error.status === 403 && error.code === "account_unavailable") {
        setView({ attempt: reloadCount, status: "unavailable" });
        return;
      }
      const failure = updateFailure(error);
      setFieldError(failure.field);
      setFeedback({ kind: "error", message: failure.message, reloadRequired: failure.reloadRequired });
      if (failure.field) focusWhenEnabled.current = true;
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void mutate();
  }

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
          <Text size="sm" c="dimmed">Your email is private and read-only. Your display name is visible to signed-in ShareWise users.</Text>
        </Stack>
        {feedback && (
          <Alert color={feedback.kind === "success" ? "indigo" : "red"}
            role={feedback.kind === "success" ? "status" : "alert"}
            title={feedback.kind === "success" ? "Profile updated" : "Update unsuccessful"}>
            <Stack gap="sm" align="flex-start">
              <Text size="sm">{feedback.message}</Text>
              {feedback.reloadRequired && (
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
                required
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
                disabled={pending || feedback?.reloadRequired}
                autoComplete="nickname"
              />
            </SimpleGrid>
            <Group justify="flex-end">
              <Button type="submit" loading={pending}
                disabled={pending || Boolean(feedback?.reloadRequired) || Boolean(fieldError) || unchanged}>Update display name</Button>
            </Group>
          </Stack>
        </form>
      </Stack>
    </Card>
  );
}

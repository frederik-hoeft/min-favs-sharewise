"use client";

import { Alert, Text } from "@mantine/core";
import Link from "next/link";
import { useAuthState } from "@/app/identity-provider";

/** The workspace is illustrative, not a protected account or finance experience. */
export function SessionNotice() {
  const state = useAuthState();
  if (state.status === 'authenticated') {
    return (
      <Alert title="Signed in for this tab" color="indigo">
        Your temporary browser session is active. This workspace is still a demo and does not display account data. Reloading or opening a new tab requires signing in again until session renewal is implemented.
      </Alert>
    );
  }
  const message = state.reason === 'expired'
    ? 'Your session has expired. Sign in again to continue.'
    : state.reason === 'unauthorized'
      ? 'Your session was rejected. Please sign in again.'
      : 'No active sign-in in this tab. You can explore this demonstration workspace without an account.';
  return (
    <Alert title="Demonstration workspace" color="gray">
      <Text size="sm">{message} <Link href="/login">Sign in</Link> or <Link href="/register">create an account</Link>.</Text>
    </Alert>
  );
}

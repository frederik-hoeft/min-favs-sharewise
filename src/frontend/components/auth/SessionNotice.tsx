"use client";

import { Alert, Button, Text } from "@mantine/core";
import { useEffect, useState } from "react";
import { safeAppReturnPath } from "@/lib/navigation/return-path";
import Link from "next/link";
import { useAuthState, useIdentity } from "@/app/identity-provider";

/** The demonstration routes remain public; identity is restored only for display. */
export function SessionNotice() {
  const state = useAuthState();
  const { auth } = useIdentity();
  const [loginHref, setLoginHref] = useState("/auth/login");

  useEffect(() => {
    const next = safeAppReturnPath(window.location.pathname + window.location.search);
    setLoginHref(`/auth/login?next=${encodeURIComponent(next)}`);
    void auth.ensureAuthenticated().catch(() => {
      // The coordinator publishes the terminal state; no component-level refresh policy.
    });
  }, [auth]);

  if (state.status === 'authenticated') {
    return <Alert title="Signed in" color="indigo">Your sign-in is active in this tab. Authentication will renew automatically when needed.</Alert>;
  }
  if (state.status === 'context-changed') {
    return (
      <Alert color="yellow" title="Your sign-in changed in another tab" role="alert">
        <Text size="sm" mb="sm">Reload this page to use the browser's current sign-in. Unsaved edits on this page may be lost.</Text>
        <Button size="xs" onClick={() => window.location.reload()}>Reload page</Button>
      </Alert>
    );
  }
  if (state.status === 'restoring' || state.reason === 'initial') {
    return <Alert title="Checking sign-in" color="gray">Restoring the browser's current session, if available.</Alert>;
  }
  const message = state.reason === 'uncertain'
    ? 'The last renewal could not be confirmed. For safety, sign in again.'
    : 'No active sign-in is available in this tab.';
  return (
    <Alert title="Demonstration workspace" color="gray">
      <Text size="sm">{message} <Link href={loginHref}>Sign in</Link> or <Link href="/auth/register">create an account</Link>. You can still explore this demo.</Text>
    </Alert>
  );
}

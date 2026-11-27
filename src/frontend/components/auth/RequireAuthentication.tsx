"use client";

import { Alert, Button, Group, Loader, Stack, Text } from "@mantine/core";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactElement, type ReactNode } from "react";
import { AuthenticatedApisProvider, useAuthState, useIdentity } from "@/app/identity-provider";
import type { AuthState, IdentityRuntime } from "@/core/identity";
import { safeAppReturnPath } from "@/lib/navigation/return-path";

/**
 * Presentation guard for the authenticated workspace. Keep navigation in
 * the presentation layer: the HTTP client owns recovery and publishes terminal
 * identity state, but never redirects or treats every application 401 as logout.
 */
export function RequireAuthentication({ children }: Readonly<{ children: ReactNode }>): ReactElement {
  const { auth, session }: Pick<IdentityRuntime, "auth" | "session"> = useIdentity();
  const state: AuthState = useAuthState();
  const pathname: string = usePathname();
  const router: ReturnType<typeof useRouter> = useRouter();

  useEffect(() => {
    void auth.ensureAuthenticated().catch(() => {
      // The coordinator owns the restoration outcome and publishes the session state.
    });
  }, [auth]);

  useEffect(() => {
    if (state.status === "reauthentication-required" &&
      (state.reason === "expired" || state.reason === "unauthorized" || state.reason === "uncertain")) {
      const next: string = safeAppReturnPath(`${pathname}${window.location.search}`);
      router.replace(`/auth/login?next=${encodeURIComponent(next)}`);
    }
  }, [pathname, router, state]);

  // The entire workspace owns one lifetime per login, not per access-token generation.
  if (state.status === "authenticated") return <AuthenticatedApisProvider key={session.getAuthenticatedLifetime()} sessionId={state.sessionId}>{children}</AuthenticatedApisProvider>;

  if (state.status === "context-changed") {
    return (
      <Alert title="Sign-in changed" color="gray" role="alert">
        <Stack gap="sm" align="flex-start">
          <Text size="sm">Another sign-in changed the browser session. Reload this page to use the current sign-in.</Text>
          <Button component="a" href={pathname} variant="light">Reload page</Button>
        </Stack>
      </Alert>
    );
  }

  if (state.status === "reauthentication-required" && state.reason === "retryable") {
    return (
      <Alert title="Unable to check sign-in" color="gray" role="alert">
        <Stack gap="sm" align="flex-start">
          <Text size="sm">The session check could not be completed. Check your connection and retry.</Text>
          <Button variant="light" onClick={() => void auth.ensureAuthenticated().catch(() => {})}>Retry session check</Button>
        </Stack>
      </Alert>
    );
  }

  return <Group role="status" gap="sm"><Loader size="sm" /><Text>Checking sign-in…</Text></Group>;
}

"use client";

import { Alert, Button, Group, Loader, Paper, Stack, Text, Title } from "@mantine/core";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthenticatedApisProvider, useAuthState, useIdentity } from "@/app/identity-provider";
import { INVITE_RETURN_QUERY } from "@/lib/navigation/invitation-routes";
import { type InvitationContinuation, useInvitationContinuation } from "@/app/invitation-continuation";
import { useGroupsApi } from "@/app/use-groups-api";
import { classifyGroupProblem, isGroupId } from "@/core/groups";
import { useExclusiveOperation } from "@/lib/hooks/use-exclusive-operation";
import { groupRoutes, groupViewHref } from "@/lib/navigation/group-routes";
import styles from "../_components/AuthenticationForm.module.scss";

export function InvitationAcceptance() {
  const { continuation, pending, revision } = useInvitationContinuation();
  const { auth, session } = useIdentity();
  const state = useAuthState();
  const lifetime = session.getAuthenticatedLifetime();

  useEffect(() => {
    if (!continuation) return;
    const owner = continuation;
    function capture() {
      const fragment = window.location.hash.slice(1);
      if (!fragment) {
        if (owner.getSnapshot() === 0) owner.capture("");
        return;
      }
      // Next copies its internal route state and synchronizes its canonical URL for external
      // history changes. Passing the existing internal state would bypass that synchronization.
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      owner.capture(fragment);
      owner.bind(session.getAuthenticatedLifetime());
    }
    // The router installs its native-history integration in a parent effect. Capture after
    // this effect flush, and never capture from a page that has already been abandoned.
    let mounted = true;
    queueMicrotask(() => { if (mounted) capture(); });
    window.addEventListener("hashchange", capture);
    return () => {
      mounted = false;
      window.removeEventListener("hashchange", capture);
    };
  }, [continuation, session]);

  useEffect(() => {
    if (pending) void auth.ensureAuthenticated().catch(() => {});
  }, [auth, pending]);
  useEffect(() => {
    if (state.status === "authenticated") continuation?.bind(lifetime);
    else if (state.status === "context-changed") continuation?.clear();
  }, [continuation, lifetime, state.status, revision]);

  return <div className={styles.container}>
    <Paper className={styles.card} radius="xl" withBorder>
      <Stack gap="lg">
        <Title order={1}>Join a group</Title>
        {revision === 0 && continuation ? <Group role="status"><Loader size="sm" /><Text>Checking invitation…</Text></Group> : !pending || !continuation ? <>
          <Text c="dimmed">This invitation is unavailable. Open the original invitation link again, or ask a group administrator for a new one.</Text>
          <Button component={Link} href={groupRoutes.list} variant="light">Go to your groups</Button>
        </> : state.status === "authenticated" ? <AuthenticatedApisProvider key={lifetime} sessionId={state.sessionId}>
          <JoinGroup key={revision} continuation={continuation} lifetime={lifetime} />
        </AuthenticatedApisProvider> : <>
          {state.status === "restoring" || (state.status === "reauthentication-required" && state.reason === "initial") ? <Group role="status"><Loader size="sm" /><Text>Checking sign-in…</Text></Group>
            : state.status === "reauthentication-required" && state.reason === "retryable" ? <>
              <Alert role="alert">Unable to check sign-in. Check your connection and retry.</Alert>
              <Button onClick={() => void auth.ensureAuthenticated().catch(() => {})}>Retry session check</Button>
            </> : <>
              <Text c="dimmed">Sign in or create an account to accept this invitation. You will be asked to confirm before joining.</Text>
              <Button component={Link} href={`/auth/login${INVITE_RETURN_QUERY}`}>Sign in</Button>
              <Button component={Link} href={`/auth/register${INVITE_RETURN_QUERY}`} variant="light">Create account</Button>
            </>}
          <Button component={Link} href="/" variant="subtle" onClick={() => continuation.clear()}>Cancel</Button>
        </>}
      </Stack>
    </Paper>
  </div>;
}

function JoinGroup({ continuation, lifetime }: { continuation: InvitationContinuation; lifetime: number | undefined }) {
  const { session } = useIdentity();
  const api = useGroupsApi();
  const router = useRouter();
  const operation = useExclusiveOperation<"join">();
  const [notice, setNotice] = useState<string | null>(null);

  function join() {
    if (session.getAuthenticatedLifetime() !== lifetime) return;
    const token = continuation.forSession(lifetime);
    if (!token) return;
    setNotice(null);
    operation.start("join", () => api.redeemInvitation(token), outcome => {
      if (session.getAuthenticatedLifetime() !== lifetime || continuation.forSession(lifetime) !== token) return;
      if (outcome.status === "fulfilled" && isGroupId(outcome.value?.groupId) && (outcome.value.outcome === "joined" || outcome.value.outcome === "already_member")) {
        continuation.clear();
        router.replace(groupViewHref(outcome.value.groupId));
        return;
      }
      const problem = outcome.status === "rejected" ? classifyGroupProblem(outcome.error) : { kind: "unavailable" };
      if (problem.kind === "invitation_unavailable") continuation.clear();
      else if (problem.kind === "user_unknown") setNotice("Your account is not yet available to group management. Try again later.");
      else setNotice("The join could not be confirmed. Check your groups before trying again; the invitation may already have been used.");
    });
  }

  const busy = operation.pending !== null;
  return <>
    <Text c="dimmed">You have received a group invitation. Join using your current ShareWise account?</Text>
    {notice ? <Alert role="alert" color="yellow">{notice}</Alert> : null}
    <Button onClick={join} loading={busy} disabled={busy}>Join group</Button>
    <Button component={Link} href={groupRoutes.list} variant="subtle" disabled={busy} onClick={event => {
      if (operation.isRunning()) event.preventDefault();
      else continuation.clear();
    }}>{notice ? "Go to your groups" : "Cancel"}</Button>
  </>;
}

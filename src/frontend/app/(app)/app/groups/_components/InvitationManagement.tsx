"use client";

import { Alert, Badge, Button, Card, Group, NativeSelect, NumberInput, Stack, Text, TextInput, Title } from "@mantine/core";
import { CopyIcon, LinkSimpleIcon } from "@phosphor-icons/react";
import { useCallback, useEffect, useRef, useState } from "react";
import { invitationHref } from "@/lib/navigation/invitation-routes";
import { useGroupsApi } from "@/app/use-groups-api";
import { ConfirmationDialog } from "@/components/feedback/ConfirmationDialog";
import { classifyGroupProblem, isInvitationToken, type GroupProblem, type InvitationSummary } from "@/core/groups";
import { useExclusiveOperation } from "@/lib/hooks/use-exclusive-operation";
import { GroupLoadingState, GroupProblemState } from "./GroupProblemState";
import { useGroupResource } from "./use-group-resource";
import styles from "./GroupInformationPanel.module.scss";

const MAX_USES = 2147483647;
const DAY = 24 * 60 * 60 * 1000;

function accessLost(problem: GroupProblem) {
  return problem.kind === "admin_required" || problem.kind === "not_found" || problem.kind === "user_unknown" || problem.kind === "unauthenticated";
}

function invitationStatus(invitation: InvitationSummary): string {
  if (invitation.expiresAt !== null && Date.parse(invitation.expiresAt) <= Date.now()) return "Expired";
  if (invitation.useCount >= (invitation.maxUses ?? MAX_USES)) return "Exhausted";
  return "Active";
}

function formatInstant(value: string): string {
  return new Date(value).toLocaleString();
}

/** Admin-only presentation. The server, not this view, authorizes every operation. */
export function InvitationManagement({ groupId, onClose, onProblem }: { groupId: string; onClose: () => void; onProblem: (problem: GroupProblem) => void }) {
  const api = useGroupsApi();
  const load = useCallback(() => api.listInvitations(groupId), [api, groupId]);
  const { state, reload } = useGroupResource(load);
  const operation = useExclusiveOperation<"create" | "revoke" | "copy">();
  const [expiry, setExpiry] = useState("7");
  const [limitKind, setLimitKind] = useState("single");
  const [limit, setLimit] = useState<number | string>(2);
  const [notice, setNotice] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<InvitationSummary | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const restoreFocus = useRef<HTMLElement | null>(null);
  const restorePending = useRef(false);
  const busy = operation.pending !== null;
  const blocked = busy || confirmation !== null;

  useEffect(() => {
    if (state.status === "failed" && accessLost(state.problem)) onProblem(state.problem);
  }, [state, onProblem]);
  useEffect(() => {
    if (restorePending.current && !confirmation && !busy) {
      restorePending.current = false;
      (restoreFocus.current?.isConnected ? restoreFocus.current : heading.current)?.focus();
    }
  }, [restorePending, confirmation, busy]);

  function reconcileFailure(error: unknown) {
    setSecret(null);
    const problem = classifyGroupProblem(error);
    if (accessLost(problem)) { onProblem(problem); return; }
    setNotice(problem.kind === "invalid" ? "The invitation settings were rejected. Check the expiration and usage limit."
      : problem.kind === "invitation_not_found" ? "This invitation is no longer available."
      : "The invitation change could not be confirmed. Review the current links before trying again. A lost creation response cannot be recovered; revoke and create a replacement if needed.");
    void reload();
  }

  function create() {
    if (blocked || secret !== null || state.status !== "ready") return;
    const maxUses = limitKind === "unlimited" ? null : limitKind === "single" ? 1 : typeof limit === "number" ? limit : NaN;
    if (maxUses !== null && (!Number.isInteger(maxUses) || maxUses < 1 || maxUses > MAX_USES)) {
      setNotice(`Enter a whole number of uses from 1 to ${MAX_USES}.`);
      return;
    }
    setNotice(null);
    operation.start("create", () => api.createInvitation(groupId, {
      maxUses,
      expiresAt: expiry === "never" ? null : new Date(Date.now() + Number(expiry) * DAY).toISOString(),
    }), outcome => {
      if (outcome.status === "rejected") { reconcileFailure(outcome.error); return; }
      if (!isInvitationToken(outcome.value?.token)) {
        reconcileFailure(new Error("Missing or malformed invitation creation acknowledgment"));
        return;
      }
      setSecret(invitationHref(outcome.value.token, window.location.origin));
      void reload();
    });
  }

  function revoke() {
    if (!confirmation) return;
    const id = confirmation.id;
    operation.start("revoke", () => api.revokeInvitation(groupId, id), outcome => {
      setConfirmation(null);
      restorePending.current = true;
      if (outcome.status === "rejected") { reconcileFailure(outcome.error); return; }
      setNotice("Invitation revoked.");
      void reload();
    });
  }

  function copy() {
    if (!secret) return;
    operation.start("copy", () => navigator.clipboard.writeText(secret), outcome => {
      setNotice(outcome.status === "fulfilled" ? "Invitation link copied." : "Automatic copying failed. Select and copy the link manually.");
    });
  }

  return <Stack gap="lg">
    <Group justify="space-between" align="center">
      <Title ref={heading} tabIndex={-1} order={2}>Invitation links</Title>
      <Button variant="subtle" disabled={blocked} onClick={() => { if (!operation.isRunning()) onClose(); }}>Back to overview</Button>
    </Group>
    {notice ? <Alert role="status" variant="light">{notice}</Alert> : null}
    {secret !== null && !(state.status === "failed" && accessLost(state.problem)) ? <Card padding="lg" withBorder>
      <Stack gap="md">
        <Title order={3} size="h4">Your invitation link</Title>
        <Text size="sm">Copy this link now. For your security, it cannot be retrieved again after closing.</Text>
        <TextInput label="Invitation link" value={secret} readOnly autoComplete="off" spellCheck={false} onFocus={event => event.currentTarget.select()} />
        <Group>
          <Button leftSection={<CopyIcon aria-hidden="true" />} loading={operation.pending === "copy"} disabled={busy} onClick={copy}>Copy link</Button>
          <Button variant="subtle" disabled={busy} onClick={() => { setSecret(null); setNotice(null); }}>Close link</Button>
        </Group>
      </Stack>
    </Card> : null}
    <Card padding="lg" withBorder>
      <form onSubmit={event => { event.preventDefault(); create(); }}>
        <Stack gap="md">
          <Title order={3} size="h4">Create invitation</Title>
          <Text size="sm" c="dimmed">Anyone with a valid link can join this group after signing in.</Text>
          <NativeSelect label="Expiration" value={expiry} onChange={event => setExpiry(event.currentTarget.value)} disabled={blocked || secret !== null}
            data={[{ value: "1", label: "1 day" }, { value: "7", label: "7 days" }, { value: "30", label: "30 days" }, { value: "never", label: "Never" }]} />
          <NativeSelect label="Maximum uses" value={limitKind} onChange={event => setLimitKind(event.currentTarget.value)} disabled={blocked || secret !== null}
            data={[{ value: "single", label: "Single use" }, { value: "finite", label: "Custom limit" }, { value: "unlimited", label: "Unlimited" }]} />
          {limitKind === "finite" ? <NumberInput label="Usage limit" min={1} max={MAX_USES} clampBehavior="none" allowDecimal={false} value={limit} onChange={setLimit} disabled={blocked || secret !== null} /> : null}
          <Button type="submit" leftSection={<LinkSimpleIcon aria-hidden="true" />} loading={operation.pending === "create"} disabled={blocked || secret !== null || state.status !== "ready"}>Create link</Button>
        </Stack>
      </form>
    </Card>
    <Card padding="lg" withBorder>
      <Stack gap="md">
        <Group justify="space-between">
          <Title order={3} size="h4">Existing invitations</Title>
          <Button variant="subtle" size="xs" disabled={blocked || state.status === "loading"} onClick={() => void reload()}>Refresh links</Button>
        </Group>
        {state.status === "loading" ? <GroupLoadingState label="Loading invitations…" /> : state.status === "failed" ?
          <GroupProblemState problem={state.problem} onRetry={() => void reload()} /> : state.data.items.length === 0 ?
          <Text c="dimmed" size="sm">No invitation links yet.</Text> : <Stack component="ul" gap="md" p={0} m={0} style={{ listStyle: "none" }} aria-label="Existing invitations">
            {state.data.items.map(invitation => <Card key={invitation.id} component="li" withBorder padding="md">
              <Stack gap="xs">
                <Group justify="space-between" wrap="wrap">
                  <Badge variant="light">{invitationStatus(invitation)}</Badge>
                  <Text size="sm" c="dimmed">{invitation.useCount} / {invitation.maxUses ?? "unlimited"} uses</Text>
                </Group>
                <Text id={`invitation-id-${invitation.id}`} size="sm" className={styles.wrap}>{invitation.id}</Text>
                <Text size="xs" c="dimmed">Created {formatInstant(invitation.createdAt)}</Text>
                <Text size="xs" c="dimmed">{invitation.expiresAt ? `Expires ${formatInstant(invitation.expiresAt)}` : "No expiration"}</Text>
                <Button color="red" variant="subtle" aria-describedby={`invitation-id-${invitation.id}`} disabled={blocked || secret !== null} onClick={event => {
                  if (operation.isRunning()) return;
                  restoreFocus.current = event.currentTarget;
                  restorePending.current = false;
                  setConfirmation(invitation);
                }}>Revoke link</Button>
              </Stack>
            </Card>)}
          </Stack>}
      </Stack>
    </Card>
    {confirmation ? <ConfirmationDialog title="Revoke invitation?" confirmLabel="Revoke link" danger pending={operation.pending === "revoke"}
      onConfirm={revoke} onCancel={() => { if (!operation.isRunning()) { setConfirmation(null); restorePending.current = true; } }}>
      <Text size="sm" className={styles.wrap}>Invitation {confirmation.id}</Text>
      <Text size="sm">This link will stop working immediately. Existing group memberships will not be affected.</Text>
    </ConfirmationDialog> : null}
  </Stack>;
}

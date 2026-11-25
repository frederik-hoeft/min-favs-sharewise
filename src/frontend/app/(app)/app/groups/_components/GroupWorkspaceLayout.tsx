"use client";

import { Alert, Stack, Title } from "@mantine/core";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { classifyGroupProblem, isGroupId, type GroupProblem, type GroupWorkspace } from "@/core/groups";
import { ApplicationShell } from "../../_components/ApplicationShell";
import { useShellAsideControls } from "../../_components/shell-aside-context";
import { appBreadcrumbs, groupRoutes, groupViewHref } from "../../_components/app-routes";
import { GroupWorkspaceContext } from "./group-workspace-context";
import { GroupEditor } from "./GroupEditor";
import { GroupInformationPanel } from "./GroupInformationPanel";
import { GroupLoadingState, GroupProblemState } from "./GroupProblemState";
import { MemberActionConfirmation } from "./MemberActionConfirmation";
import { executeMembershipAction, type MembershipAction } from "./membership-actions";
import { useExclusiveOperation } from "./use-exclusive-operation";
import { useGroupsApi } from "./use-groups-api";
import { useGroupResource } from "./use-group-resource";
import styles from "./GroupInformationPanel.module.scss";

/** Route composition owns the group resource; the shell has no Group Management dependency. */
export function GroupWorkspaceLayout({ children }: { children: ReactNode }) {
  const id = useSearchParams().get("id");
  if (!isGroupId(id)) return <ApplicationShell><GroupProblemState problem={{ kind: "not_found" }} /></ApplicationShell>;
  return <GroupWorkspaceOwner key={id} groupId={id}>{children}</GroupWorkspaceOwner>;
}

function membershipFailureMessage(problem: GroupProblem): string {
  switch (problem.kind) {
    case "admin_required": return "You are no longer an administrator of this group. No change was made.";
    case "member_not_found": return "That membership is no longer available. No change was made.";
    case "last_member": return "A group must retain at least one member.";
    case "last_administrator": return "A group must retain at least one administrator.";
    case "invalid_transfer": return "Administration cannot be transferred to yourself.";
    case "invalid": return "The membership request was rejected.";
    default: return "The outcome of the membership change could not be confirmed. Review the current group before trying again.";
  }
}

function GroupWorkspaceOwner({ groupId, children }: { groupId: string; children: ReactNode }) {
  const api = useGroupsApi();
  const path = usePathname();
  const router = useRouter();
  const load = useCallback(() => api.getWorkspace(groupId), [api, groupId]);
  const { state, reload, replace } = useGroupResource(load);
  const operation = useExclusiveOperation<"membership">();
  const [editing, setEditing] = useState(false);
  const [confirmation, setConfirmation] = useState<{ action: MembershipAction; restoreFocus: () => void } | null>(null);
  const canceledFocus = useRef<(() => void) | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pageProblem, setPageProblem] = useState<GroupProblem | null>(null);
  const [left, setLeft] = useState(false);
  useEffect(() => {
    if (confirmation === null && canceledFocus.current) {
      canceledFocus.current();
      canceledFocus.current = null;
    }
  }, [confirmation]);
  const status = notice ? <Alert variant="light" role="status">{notice}</Alert> : null;
  if (left) return <ApplicationShell><GroupLoadingState label="Returning to your groups…" /></ApplicationShell>;
  if (pageProblem) return <ApplicationShell><Stack>{status}<GroupProblemState problem={pageProblem} /></Stack></ApplicationShell>;
  if (state.status === "loading") return <ApplicationShell><GroupLoadingState label="Loading group…" /></ApplicationShell>;
  if (state.status === "failed") return <ApplicationShell><Stack>{status}<GroupProblemState problem={state.problem} onRetry={() => void reload()} /></Stack></ApplicationShell>;
  const workspace = state.data;
  const group = workspace.group;
  const breadcrumbs = appBreadcrumbs(path).map(crumb => crumb.href === groupRoutes.view ? { label: group.name, href: groupViewHref(groupId) } : crumb);

  function handleMetadataProblem(problem: GroupProblem) {
    setEditing(false);
    if (problem.kind === "admin_required") {
      setNotice("You are no longer an administrator of this group, so your changes were not saved.");
      void reload();
    } else setPageProblem(problem);
  }

  function confirmMembershipAction() {
    if (!confirmation || editing) return;
    const { action } = confirmation;
    operation.start("membership", () => executeMembershipAction(api, groupId, action), outcome => {
      setConfirmation(null);
      if (outcome.status === "fulfilled") {
        if (action.kind === "leave") {
          // Do not keep private data visible while the navigation is in progress.
          setLeft(true);
          router.replace(groupRoutes.list);
          return;
        }
        setNotice("Membership updated.");
      } else {
        const problem = classifyGroupProblem(outcome.error);
        if (problem.kind === "not_found" || problem.kind === "user_unknown" || problem.kind === "unauthenticated") {
          setPageProblem(problem);
          return;
        }
        setNotice(membershipFailureMessage(problem));
      }
      // Rejections can reflect concurrent changes; an uncertain write may already have committed.
      // Reload rather than replay the command, and do not present the stale roster as current.
      void reload();
    });
  }

  return (
    <GroupWorkspaceContext.Provider value={workspace}>
      <ApplicationShell breadcrumbs={breadcrumbs} aside={{
        label: "Group information",
        content: <GroupWorkspaceAside
          workspace={workspace}
          editing={editing}
          busy={confirmation !== null || operation.pending !== null}
          onEdit={() => {
            if (confirmation || operation.isRunning()) return;
            setNotice(null);
            setEditing(true);
          }}
          onMemberAction={(action, restoreFocus) => {
            if (editing || confirmation || operation.isRunning()) return;
            setNotice(null);
            setConfirmation({ action, restoreFocus });
          }}
        />,
      }}>
        <Stack gap="xl">
          <Title order={1} className={styles.wrap}>{group.name}</Title>
          {status}
          {editing && group.callerRole === "admin" ? <GroupEditor
            group={group}
            onCancel={() => setEditing(false)}
            onSaved={() => { setEditing(false); setNotice("Changes saved."); void reload(); }}
            onRefreshed={current => { replace(current); if (current.group.callerRole !== "admin") setEditing(false); }}
            onProblem={handleMetadataProblem}
          /> : children}
        </Stack>
        {confirmation ? <MemberActionConfirmation
          action={confirmation.action}
          own={confirmation.action.member.id === workspace.callerMembershipId}
          pending={operation.pending !== null}
          onConfirm={confirmMembershipAction}
          onCancel={() => {
            if (operation.isRunning()) return;
            canceledFocus.current = confirmation.restoreFocus;
            setConfirmation(null);
          }}
        /> : null}
      </ApplicationShell>
    </GroupWorkspaceContext.Provider>
  );
}

/** Integrates a shell-independent information panel with the shell's responsive disclosure. */
function GroupWorkspaceAside({ workspace, editing, busy, onEdit, onMemberAction }: {
  workspace: GroupWorkspace;
  editing: boolean;
  busy: boolean;
  onEdit: () => void;
  onMemberAction: (action: MembershipAction, restoreFocus: () => void) => void;
}) {
  const { dismiss, restoreFocus } = useShellAsideControls();
  return <GroupInformationPanel workspace={workspace} editing={editing} busy={busy}
    onEdit={() => { dismiss(); onEdit(); }}
    onMemberAction={(action, origin) => { dismiss(); onMemberAction(action, () => restoreFocus(origin)); }}
  />;
}

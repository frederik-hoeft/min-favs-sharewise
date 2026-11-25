"use client";

import { Alert, Stack, Title } from "@mantine/core";
import { usePathname, useSearchParams } from "next/navigation";
import { useCallback, useState, type ReactNode } from "react";
import { isGroupId, type GroupProblem } from "@/core/groups";
import { ApplicationShell } from "../../_components/ApplicationShell";
import { appBreadcrumbs, groupRoutes, groupViewHref } from "../../_components/app-routes";
import { GroupEditor } from "./GroupEditor";
import { GroupInformationPanel } from "./GroupInformationPanel";
import { GroupLoadingState, GroupProblemState } from "./GroupProblemState";
import { useGroupsApi } from "./use-groups-api";
import { useGroupResource } from "./use-group-resource";
import styles from "./GroupInformationPanel.module.scss";

/** Route composition owns the group resource; the shell has no Group Management dependency. */
export function GroupWorkspaceLayout({ children }: { children: ReactNode }) {
  const id = useSearchParams().get("id");
  if (!isGroupId(id)) return <ApplicationShell><GroupProblemState problem={{ kind: "not_found" }} /></ApplicationShell>;
  return <GroupWorkspaceOwner key={id} groupId={id}>{children}</GroupWorkspaceOwner>;
}

function GroupWorkspaceOwner({ groupId, children }: { groupId: string; children: ReactNode }) {
  const api = useGroupsApi();
  const path = usePathname();
  const load = useCallback(() => api.getWorkspace(groupId), [api, groupId]);
  const { state, reload, replace } = useGroupResource(load);
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pageProblem, setPageProblem] = useState<GroupProblem | null>(null);
  if (pageProblem) return <ApplicationShell><GroupProblemState problem={pageProblem} /></ApplicationShell>;
  if (state.status === "loading") return <ApplicationShell><GroupLoadingState label="Loading group…" /></ApplicationShell>;
  if (state.status === "failed") return <ApplicationShell><GroupProblemState problem={state.problem} onRetry={() => void reload()} /></ApplicationShell>;
  const workspace = state.data;
  const group = workspace.group;
  const breadcrumbs = appBreadcrumbs(path).map(crumb => crumb.href === groupRoutes.view ? { label: group.name, href: groupViewHref(groupId) } : crumb);
  function handleProblem(problem: GroupProblem) {
    setEditing(false);
    if (problem.kind === "admin_required") {
      setNotice("You are no longer an administrator of this group, so your changes were not saved.");
      void reload();
    } else setPageProblem(problem);
  }
  return <ApplicationShell breadcrumbs={breadcrumbs} aside={{ label: "Group information", content: dismiss => <GroupInformationPanel workspace={workspace} editing={editing} onEdit={() => { dismiss(); setNotice(null); setEditing(true); }} /> }}>
    <Stack gap="xl">
      <Title order={1} className={styles.wrap}>{group.name}</Title>
      {notice ? <Alert variant="light" role="status">{notice}</Alert> : null}
      {editing && group.callerRole === "admin" ? <GroupEditor group={group} onCancel={() => setEditing(false)} onSaved={() => { setEditing(false); setNotice("Changes saved."); void reload(); }} onRefreshed={current => { replace(current); if (current.group.callerRole !== "admin") setEditing(false); }} onProblem={handleProblem} /> : children}
    </Stack>
  </ApplicationShell>;
}

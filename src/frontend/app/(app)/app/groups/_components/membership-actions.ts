import type { GroupMember, GroupRole, GroupWorkspace, GroupsApi } from "@/core/groups";

/** Commands target the observed membership identity, never a user's replacement membership. */
export type MembershipAction =
  | { kind: "role"; member: GroupMember; role: GroupRole }
  | { kind: "remove" | "leave"; member: GroupMember };

export interface MembershipOption {
  action: MembershipAction;
  label: string;
  disabled?: boolean;
  danger?: boolean;
}

/** Presentation affordances only; the server re-evaluates authorization and invariants. */
export function memberOptions(workspace: GroupWorkspace, member: GroupMember, administratorCount: number): MembershipOption[] {
  if (workspace.group.callerRole !== "admin" || member.id === workspace.callerMembershipId) return [];
  const finalAdministrator = member.role === "admin" && administratorCount <= 1;
  const role = member.role === "admin" ? "member" : "admin";
  const options: MembershipOption[] = [{
    action: { kind: "role", member, role },
    label: role === "admin" ? "Make administrator" : "Make member",
    disabled: finalAdministrator,
  }];
  options.push(
    { action: { kind: "remove", member }, label: "Remove member", danger: true, disabled: finalAdministrator },
  );
  return options;
}

export function executeMembershipAction(api: GroupsApi, groupId: string, action: MembershipAction): Promise<void> {
  switch (action.kind) {
    case "role": return api.setMemberRole(groupId, action.member.id, action.role);
    case "remove":
    case "leave": return api.removeMember(groupId, action.member.id);
  }
}

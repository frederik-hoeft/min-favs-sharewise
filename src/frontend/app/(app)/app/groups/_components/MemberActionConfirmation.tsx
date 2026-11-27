"use client";

import { Text } from "@mantine/core";
import { ConfirmationDialog } from "@/components/feedback/ConfirmationDialog";
import type { MembershipAction } from "./membership-actions";
import { UserIdentity } from "@/components/participants/UserIdentity";
import type { ProfileResolution } from "@/core/identity";

function confirmationOf(action: MembershipAction) {
  switch (action.kind) {
    case "role": return action.role === "admin"
      ? { title: "Make admin?", label: "Make admin", message: "This member will be able to manage membership, invitations and group details." }
      : { title: "Make member?", label: "Make member", message: "This member will lose admin permissions but remain in the group." };
    case "remove": return { title: "Remove member?", label: "Remove member", message: "This member will lose access to the group.", danger: true };
    case "leave": return { title: "Leave group?", label: "Leave group", message: "You will lose access to this group. You will need a valid invitation to join again.", danger: true };
  }
}

export function MemberActionConfirmation({ action, own, profile, secondaryId, pending, onConfirm, onCancel }: { action: MembershipAction; own: boolean; profile?: ProfileResolution; secondaryId?: string; pending: boolean; onConfirm: () => void; onCancel: () => void }) {
  const confirmation = confirmationOf(action);
  return <ConfirmationDialog title={confirmation.title} confirmLabel={confirmation.label} danger={confirmation.danger} pending={pending} onConfirm={onConfirm} onCancel={onCancel}>
    <UserIdentity userId={action.member.userId} profile={profile} secondaryId={secondaryId} own={own} wrapName />
    <Text size="sm">{confirmation.message}</Text>
  </ConfirmationDialog>;
}

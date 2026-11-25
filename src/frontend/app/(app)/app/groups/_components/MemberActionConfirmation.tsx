"use client";

import { Text } from "@mantine/core";
import { ConfirmationDialog } from "@/components/feedback/ConfirmationDialog";
import type { MembershipAction } from "./membership-actions";
import styles from "./GroupInformationPanel.module.scss";

function confirmationOf(action: MembershipAction, own: boolean) {
  switch (action.kind) {
    case "role": return action.role === "admin"
      ? { title: "Make administrator?", label: "Make administrator", message: "This member will be able to manage membership, invitations and group details." }
      : { title: own ? "Give up administration?" : "Make member?", label: "Make member", message: own ? "You will remain a member but lose administrator permissions." : "This member will lose administrator permissions but remain in the group." };
    case "transfer": return { title: "Transfer administration?", label: "Transfer administration", message: "This member becomes (or remains) an administrator, and you become a regular member in the same operation." };
    case "remove": return { title: "Remove member?", label: "Remove member", message: "This member will lose access to the group.", danger: true };
    case "leave": return { title: "Leave group?", label: "Leave group", message: "You will lose access to this group. You will need a valid invitation to join again.", danger: true };
  }
}

export function MemberActionConfirmation({ action, own, pending, onConfirm, onCancel }: { action: MembershipAction; own: boolean; pending: boolean; onConfirm: () => void; onCancel: () => void }) {
  const confirmation = confirmationOf(action, own);
  return <ConfirmationDialog title={confirmation.title} confirmLabel={confirmation.label} danger={confirmation.danger} pending={pending} onConfirm={onConfirm} onCancel={onCancel}>
    {action.kind !== "leave" ? <Text size="sm" className={styles.wrap} fw={600}>{action.member.userId}{own ? " (You)" : ""}</Text> : null}
    <Text size="sm">{confirmation.message}</Text>
  </ConfirmationDialog>;
}

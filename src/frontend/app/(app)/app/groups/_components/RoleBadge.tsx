import { Badge } from "@mantine/core";
import type { GroupRole } from "@/core/groups";

const labels: Record<GroupRole, string> = { admin: "Administrator", member: "Member" };

export function RoleBadge({ role }: { role: GroupRole }) {
  return (
    <Badge variant={role === "admin" ? "filled" : "light"} aria-label={`Your role: ${labels[role]}`}>
      {labels[role]}
    </Badge>
  );
}

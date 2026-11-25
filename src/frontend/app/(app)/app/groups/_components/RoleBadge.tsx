import { Badge } from "@mantine/core";
import type { GroupRole } from "@/core/groups";

const labels: Record<GroupRole, string> = { admin: "Administrator", member: "Member" };

export function RoleBadge({ role, own = true }: { role: GroupRole; own?: boolean }) {
  return (
    <Badge variant={role === "admin" ? "filled" : "light"} aria-label={own ? `Your role: ${labels[role]}` : undefined}>
      {labels[role]}
    </Badge>
  );
}

import { Badge } from "@mantine/core";
import type { GroupRole } from "@/core/groups";

const labels: Record<GroupRole, string> = { admin: "Admin", member: "Member" };

export function RoleBadge({ role, own = true }: { role: GroupRole; own?: boolean }) {
  return (
    <Badge flex="0 0 auto" variant={role === "admin" ? "filled" : "light"} aria-label={own ? `Your role: ${labels[role]}` : undefined}>
      {labels[role]}
    </Badge>
  );
}

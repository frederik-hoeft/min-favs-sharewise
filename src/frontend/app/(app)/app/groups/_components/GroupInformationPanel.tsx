"use client";

import { Avatar, Button, Divider, Group, Stack, Text, Title } from "@mantine/core";
import { PencilSimpleIcon, UserIcon } from "@phosphor-icons/react";
import type { GroupWorkspace } from "@/core/groups";
import { GroupAvatar } from "./GroupAvatar";
import { RoleBadge } from "./RoleBadge";
import styles from "./GroupInformationPanel.module.scss";

export function GroupInformationPanel({ workspace, onEdit, editing }: { workspace: GroupWorkspace; onEdit: () => void; editing: boolean }) {
  const { group, members, callerMembershipId } = workspace;
  return <Stack gap="lg" className={styles.panel}>
    <Stack align="center" gap="sm">
      <GroupAvatar size="4rem" />
      <Title order={2} size="h3" ta="center" className={styles.wrap}>{group.name}</Title>
      <Text size="sm" c="dimmed" className={styles.description}>{group.description ?? "No description"}</Text>
      {group.callerRole === "admin" && !editing ? <Button variant="subtle" size="xs" leftSection={<PencilSimpleIcon aria-hidden="true" />} onClick={onEdit}>Edit details</Button> : null}
    </Stack>
    <Divider />
    <Title order={2} size="h4">Members ({members.length})</Title>
    <ul className={styles.members} aria-label="Group members">
      {members.map(member => <li key={member.id}>
        <Group wrap="nowrap" gap="sm" align="flex-start">
          <Avatar aria-hidden="true" size="sm"><UserIcon /></Avatar>
          <Stack gap={4} flex={1} miw={0}>
            <Text size="sm" fw={600} className={styles.wrap}>{member.userId}{member.id === callerMembershipId ? " (You)" : ""}</Text>
            <RoleBadge role={member.role} own={member.id === callerMembershipId} />
          </Stack>
        </Group>
      </li>)}
    </ul>
  </Stack>;
}

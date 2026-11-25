"use client";

import { ActionIcon, Avatar, Button, Divider, Group, Menu, Stack, Text, Title } from "@mantine/core";
import { DotsThreeIcon, LinkSimpleIcon, PencilSimpleIcon, SignOutIcon, UserIcon } from "@phosphor-icons/react";
import { useRef } from "react";
import type { GroupMember, GroupWorkspace } from "@/core/groups";
import { GroupAvatar } from "./GroupAvatar";
import { memberOptions, type MembershipAction, type MembershipOption } from "./membership-actions";
import { RoleBadge } from "./RoleBadge";
import styles from "./GroupInformationPanel.module.scss";

interface GroupInformationPanelProps {
  workspace: GroupWorkspace;
  onEdit: () => void;
  onInvitations: () => void;
  onMemberAction: (action: MembershipAction, origin: HTMLElement | null) => void;
  editing: boolean;
  busy: boolean;
}

export function GroupInformationPanel({ workspace, onEdit, onInvitations, onMemberAction, editing, busy }: GroupInformationPanelProps) {
  const { group, members, callerMembershipId } = workspace;
  const administratorCount = members.filter(member => member.role === "admin").length;
  const caller = members.find(member => member.id === callerMembershipId);
  const leaveBlocked = members.length <= 1 || (caller?.role === "admin" && administratorCount <= 1);
  const controlsDisabled = editing || busy;
  return (
    <Stack gap="lg" className={styles.panel}>
      <Stack align="center" gap="sm">
        <GroupAvatar size="4rem" />
        <Title order={2} size="h3" ta="center" className={styles.wrap}>{group.name}</Title>
        <Text size="sm" c="dimmed" className={styles.description}>{group.description ?? "No description"}</Text>
        {group.callerRole === "admin" && !editing ? <Button variant="subtle" size="xs" disabled={busy} leftSection={<PencilSimpleIcon aria-hidden="true" />} onClick={onEdit}>Edit details</Button> : null}
      </Stack>
      <Divider />
      <Title order={2} size="h4">Members ({members.length})</Title>
      <ul className={styles.members} aria-label="Group members">
        {members.map(member => {
          const own = member.id === callerMembershipId;
          const options = memberOptions(workspace, member, administratorCount);
          return <li key={member.id}>
            <Group wrap="nowrap" gap="sm" align="flex-start">
              <Avatar aria-hidden="true" size="sm"><UserIcon /></Avatar>
              <Stack gap={4} flex={1} miw={0}>
                <Text size="sm" fw={600} className={styles.wrap}>{member.userId}{own ? " (You)" : ""}</Text>
                <RoleBadge role={member.role} own={own} />
              </Stack>
              {options.length > 0 ? <MemberMenu member={member} own={own} options={options} disabled={controlsDisabled} onSelect={onMemberAction} /> : null}
            </Group>
          </li>;
        })}
      </ul>
      <Divider />
      {group.callerRole === "admin" ? <Button variant="light" fullWidth disabled={controlsDisabled} leftSection={<LinkSimpleIcon aria-hidden="true" />} onClick={onInvitations}>Manage invitation links</Button> : null}
      {leaveBlocked ? <Text size="xs" c="dimmed">{members.length <= 1 ? "You cannot leave as the only member. Invite another member first." : "You cannot leave as the only administrator. Promote another member or transfer administration first."}</Text> : null}
      <Button variant="subtle" color="red" fullWidth disabled={controlsDisabled || leaveBlocked || !caller} leftSection={<SignOutIcon aria-hidden="true" />} onClick={event => { if (caller) onMemberAction({ kind: "leave", member: caller }, event.currentTarget); }}>Leave group</Button>
    </Stack>
  );
}

function MemberMenu({ member, own, options, disabled, onSelect }: { member: GroupMember; own: boolean; options: MembershipOption[]; disabled: boolean; onSelect: (action: MembershipAction, origin: HTMLElement | null) => void }) {
  const trigger = useRef<HTMLButtonElement>(null);
  const label = own ? "Manage your membership" : `Manage member ${member.userId}`;
  return <Menu position="bottom-end" width="14rem" withinPortal={false}>
    <Menu.Target>
      <ActionIcon ref={trigger} variant="subtle" color="gray" size="lg" aria-label={label} title={label} disabled={disabled}><DotsThreeIcon weight="bold" size="1.25rem" aria-hidden="true" /></ActionIcon>
    </Menu.Target>
    <Menu.Dropdown aria-label={label}>
      {options.map(option => <Menu.Item key={option.action.kind} disabled={option.disabled} color={option.danger ? "red" : undefined} onClick={() => onSelect(option.action, trigger.current)}>{option.label}</Menu.Item>)}
    </Menu.Dropdown>
  </Menu>;
}

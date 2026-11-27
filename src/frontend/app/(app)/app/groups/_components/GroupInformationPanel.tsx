"use client";

import { ActionIcon, Button, Divider, Group, Menu, Stack, Text, Title } from "@mantine/core";
import { DotsThreeIcon, LinkSimpleIcon, PencilSimpleIcon, SignOutIcon } from "@phosphor-icons/react";
import { useRef } from "react";
import type { GroupWorkspace } from "@/core/groups";
import { GroupAvatar } from "./GroupAvatar";
import { memberOptions, type MembershipAction, type MembershipOption } from "./membership-actions";
import { RoleBadge } from "./RoleBadge";
import styles from "./GroupInformationPanel.module.scss";
import { UserIdentity } from "@/components/participants/UserIdentity";
import { shortUserId, userDisplayLabel } from "@/components/participants/user-presentation";
import type { ProfileResolution } from "@/core/identity";

interface GroupInformationPanelProps {
  workspace: GroupWorkspace;
  profiles: { values: ReadonlyMap<string, ProfileResolution>; loading: boolean; unavailable: boolean; retry: () => void };
  secondaryIds: ReadonlyMap<string, string>;
  onEdit: () => void;
  onInvitations: () => void;
  onMemberAction: (action: MembershipAction, origin: HTMLElement | null) => void;
  editing: boolean;
  busy: boolean;
}

export function GroupInformationPanel({ workspace, profiles, secondaryIds, onEdit, onInvitations, onMemberAction, editing, busy }: GroupInformationPanelProps) {
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
      {profiles.loading && <Text size="xs" c="dimmed" role="status">Resolving member names…</Text>}
      {profiles.unavailable && !profiles.loading && <Stack gap="xs">
        <Text size="xs" c="dimmed" role="status">Some member names could not be loaded.</Text>
        <Button size="xs" variant="subtle" onClick={profiles.retry}>Retry names</Button>
      </Stack>}
      <ul className={styles.members} aria-label="Group members">
        {members.map(member => {
          const own = member.id === callerMembershipId;
          const options = memberOptions(workspace, member, administratorCount);
          const profile = profiles.values.get(member.userId);
          const secondaryId = secondaryIds.get(member.userId) ?? shortUserId(member.userId);
          return <li key={member.id}>
            <Group wrap="nowrap" gap="sm" align="center">
              <Stack gap={4} flex={1} miw={0}>
                <UserIdentity userId={member.userId} profile={profile} secondaryId={secondaryId} own={own}
                  nameAccessory={<RoleBadge role={member.role} own={own} />} />
              </Stack>
              {options.length > 0 ? <MemberMenu label={`Manage member ${userDisplayLabel(profile)} (${secondaryId})`} options={options} disabled={controlsDisabled} onSelect={onMemberAction} /> : null}
            </Group>
          </li>;
        })}
      </ul>
      <Divider />
      {group.callerRole === "admin" ? <Button variant="light" fullWidth disabled={controlsDisabled} leftSection={<LinkSimpleIcon aria-hidden="true" />} onClick={onInvitations}>Manage invitation links</Button> : null}
      {leaveBlocked ? <Text size="xs" c="dimmed">{members.length <= 1 ? "You cannot leave as the only member. Invite another member first." : "You cannot leave as the only admin. Promote another member first."}</Text> : null}
      <Button variant="subtle" color="red" fullWidth disabled={controlsDisabled || leaveBlocked || !caller} leftSection={<SignOutIcon aria-hidden="true" />} onClick={event => { if (caller) onMemberAction({ kind: "leave", member: caller }, event.currentTarget); }}>Leave group</Button>
    </Stack>
  );
}

function MemberMenu({ label, options, disabled, onSelect }: { label: string; options: MembershipOption[]; disabled: boolean; onSelect: (action: MembershipAction, origin: HTMLElement | null) => void }) {
  const trigger = useRef<HTMLButtonElement>(null);
  return <Menu position="bottom-end" width="14rem" withinPortal={false}>
    <Menu.Target>
      <ActionIcon ref={trigger} variant="subtle" color="gray" size="lg" flex="0 0 auto" aria-label={label} disabled={disabled}><DotsThreeIcon weight="bold" size="1.25rem" aria-hidden="true" /></ActionIcon>
    </Menu.Target>
    <Menu.Dropdown aria-label={label}>
      {options.map(option => <Menu.Item key={option.action.kind} disabled={option.disabled} color={option.danger ? "red" : undefined} onClick={() => onSelect(option.action, trigger.current)}>{option.label}</Menu.Item>)}
    </Menu.Dropdown>
  </Menu>;
}

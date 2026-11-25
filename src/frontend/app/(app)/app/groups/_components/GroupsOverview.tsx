"use client";

import { Anchor, Button, Card, Group, Stack, Text, Title } from "@mantine/core";
import { PlusIcon, UsersThreeIcon } from "@phosphor-icons/react";
import Link from "next/link";
import { useCallback } from "react";
import type { GroupSummary } from "@/core/groups";
import { useGroupsApi } from "./use-groups-api";
import { groupRoutes, groupViewHref } from "../../_components/app-routes";
import { memberCountLabel } from "./group-format";
import { GroupLoadingState, GroupProblemState } from "./GroupProblemState";
import { RoleBadge } from "./RoleBadge";
import { useGroupResource } from "./use-group-resource";
import styles from "./GroupsOverview.module.scss";

export function GroupsOverview() {
  const api = useGroupsApi();
  const load = useCallback(() => api.list(), [api]);
  const { state, reload } = useGroupResource(load);

  return (
    <Stack gap="xl">
      <Group justify="space-between" align="flex-end" gap="md">
        <Stack gap="xs">
          <Title order={1}>Groups</Title>
          <Text c="dimmed" maw="36rem">The groups you belong to. Each group keeps its own members and shared expenses.</Text>
        </Stack>
        <CreateGroupButton />
      </Group>
      {state.status === "loading" ? <GroupLoadingState label="Loading your groups…" /> : null}
      {state.status === "failed" ? <GroupProblemState problem={state.problem} onRetry={() => void reload()} /> : null}
      {state.status === "ready" && state.data.items.length === 0 ? <EmptyGroups /> : null}
      {state.status === "ready" && state.data.items.length > 0 ? <GroupCards groups={state.data.items} /> : null}
    </Stack>
  );
}

function CreateGroupButton() {
  return (
    <Button component={Link} href={groupRoutes.create} leftSection={<PlusIcon aria-hidden="true" />}>
      Create group
    </Button>
  );
}

function EmptyGroups() {
  return (
    <Card padding="xl">
      <Stack align="flex-start" gap="sm">
        <UsersThreeIcon size="2rem" aria-hidden="true" />
        <Title order={2} size="h3">No groups yet</Title>
        <Text c="dimmed" maw="36rem">Create a group to start sharing expenses. You become its administrator.</Text>
      </Stack>
    </Card>
  );
}

function GroupCards({ groups }: { groups: ReadonlyArray<GroupSummary> }) {
  return (
    <ul className={styles.list} aria-label="Your groups">
      {groups.map((group) => (
        <li key={group.id}>
          <Card padding="lg" className={styles.card}>
            <Stack gap="xs">
              <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
                <Title order={2} size="h4" className={styles.name}>
                  <Anchor component={Link} href={groupViewHref(group.id)} inherit>{group.name}</Anchor>
                </Title>
                <RoleBadge role={group.callerRole} />
              </Group>
              {group.description ? <Text c="dimmed" lineClamp={2} className={styles.description}>{group.description}</Text> : null}
              <Text size="sm" c="dimmed">{memberCountLabel(group.memberCount)}</Text>
            </Stack>
          </Card>
        </li>
      ))}
    </ul>
  );
}

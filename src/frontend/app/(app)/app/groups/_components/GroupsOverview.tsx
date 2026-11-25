"use client";

import { Button, Card, Group, Paper, Stack, Text, TextInput, Title } from "@mantine/core";
import { CaretRightIcon, MagnifyingGlassIcon, PlusIcon, UsersThreeIcon } from "@phosphor-icons/react";
import Link from "next/link";
import { useCallback, useState } from "react";
import type { GroupSummary } from "@/core/groups";
import { useGroupsApi } from "./use-groups-api";
import { groupRoutes, groupViewHref } from "../../_components/app-routes";
import { memberCountLabel } from "./group-format";
import { GroupLoadingState, GroupProblemState } from "./GroupProblemState";
import { GroupAvatar } from "./GroupAvatar";
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
      {state.status === "ready" && state.data.items.length > 0 ? <GroupResults groups={state.data.items} /> : null}
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

function GroupResults({ groups }: { groups: ReadonlyArray<GroupSummary> }) {
  const [search, setSearch] = useState("");
  const query = search.trim().toLowerCase();
  const matches = groups.filter(({ name, description }) => name.toLowerCase().includes(query) || description?.toLowerCase().includes(query));
  return <Stack gap="md">
    <TextInput aria-label="Search groups" placeholder="Search groups by name or description" value={search} onChange={(event) => setSearch(event.currentTarget.value)} leftSection={<MagnifyingGlassIcon aria-hidden="true" />} />
    {matches.length === 0 ? <Text role="status" c="dimmed">No groups match your search.</Text> : <Paper withBorder className={styles.results}>
      <div className={styles.header} aria-hidden="true"><span>Group name</span><span>Members</span></div>
      <ul className={styles.list} aria-label="Your groups">
        {matches.map((group) => <li key={group.id}>
          <Link className={styles.row} href={groupViewHref(group.id)} aria-label={group.name} aria-describedby={`group-count-${group.id}`}>
            <GroupAvatar />
            <Text fw={600} truncate title={group.name}>{group.name}</Text>
            <Text id={`group-count-${group.id}`} size="sm" c="dimmed" className={styles.count}>{memberCountLabel(group.memberCount)}</Text>
            <CaretRightIcon aria-hidden="true" className={styles.chevron} />
          </Link>
        </li>)}
      </ul>
    </Paper>}
  </Stack>;
}

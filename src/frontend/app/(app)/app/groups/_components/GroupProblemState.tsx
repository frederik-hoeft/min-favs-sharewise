"use client";

import { Button, Card, Group, Loader, Stack, Text, Title } from "@mantine/core";
import { ArrowClockwiseIcon, ArrowLeftIcon, SignInIcon } from "@phosphor-icons/react";
import Link from "next/link";
import type { GroupProblem } from "@/core/groups";
import { groupRoutes } from "../../_components/app-routes";

type PageProblem = Exclude<GroupProblem["kind"], "version_conflict" | "invalid" | "admin_required">;

const content: Record<PageProblem, { title: string; text: string }> = {
  unauthenticated: {
    title: "Sign in required",
    text: "Sign in to see and manage your groups.",
  },
  user_unknown: {
    title: "Account not yet available",
    text: "Your account is not yet available to group management. Try again later or contact the operator of this ShareWise instance.",
  },
  not_found: {
    title: "Group not found",
    text: "This group does not exist, or you are not a member of it.",
  },
  unavailable: {
    title: "Groups are unavailable",
    text: "ShareWise could not reach group management. Check your connection and try again.",
  },
};

/** Page-level state for a request that could not show its content. */
export function GroupProblemState({ problem, onRetry }: { problem: GroupProblem; onRetry?: () => void }) {
  // Form-level problems reaching a page indicate a contract mismatch; offer a retry.
  const kind: PageProblem = problem.kind === "version_conflict" || problem.kind === "invalid"
    || problem.kind === "admin_required" ? "unavailable" : problem.kind;
  const { title, text } = content[kind];

  return (
    <Card padding="xl" role="alert" aria-labelledby="group-problem-title">
      <Stack align="flex-start" gap="sm">
        <Title id="group-problem-title" order={2} size="h3">{title}</Title>
        <Text c="dimmed" maw="36rem">{text}</Text>
        {kind === "unavailable" && onRetry ? (
          <Button variant="light" leftSection={<ArrowClockwiseIcon aria-hidden="true" />} onClick={onRetry}>
            Try again
          </Button>
        ) : null}
        {kind === "unauthenticated" ? (
          <Button component={Link} href="/auth/login" variant="light" leftSection={<SignInIcon aria-hidden="true" />}>
            Sign in
          </Button>
        ) : null}
        {kind === "not_found" ? (
          <Button component={Link} href={groupRoutes.list} variant="light" leftSection={<ArrowLeftIcon aria-hidden="true" />}>
            Back to groups
          </Button>
        ) : null}
      </Stack>
    </Card>
  );
}

export function GroupLoadingState({ label }: { label: string }) {
  return (
    <Group gap="sm" role="status">
      <Loader size="sm" aria-hidden="true" />
      <Text c="dimmed">{label}</Text>
    </Group>
  );
}

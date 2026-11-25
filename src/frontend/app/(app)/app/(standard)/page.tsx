import { Card, Stack, Text, Title } from "@mantine/core";
import Link from "next/link";
import { groupRoutes } from "../_components/app-routes";

export default function WorkspacePage() {
  return <Stack gap="xl"><Title order={1}>Your workspace.</Title><Card padding="xl"><Stack align="flex-start"><Title order={2} size="h3">Groups</Title><Text c="dimmed">Manage your groups and shared expenses.</Text><Link href={groupRoutes.list}>Open groups</Link></Stack></Card></Stack>;
}

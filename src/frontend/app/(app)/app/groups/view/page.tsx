import type { Metadata } from "next";
import { Card, Stack, Text, Title } from "@mantine/core";

export const metadata: Metadata = { title: "Group" };

export default function GroupViewPage() {
  return <Stack gap="md"><Title order={2} size="h3">Overview</Title><Card padding="xl"><Stack gap="xs"><Title order={3} size="h4">Your group workspace</Title><Text c="dimmed">Expenses, balances and history will appear here.</Text></Stack></Card></Stack>;
}

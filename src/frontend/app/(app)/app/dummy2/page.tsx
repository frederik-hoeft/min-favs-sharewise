import type { Metadata } from "next";
import { Badge, Card, Stack, Text, Title } from "@mantine/core";

export const metadata: Metadata = { title: "Dummy 2" };

export default function DummyTwoPage() {
  return (
    <Stack gap="xl">
      <Stack gap="xs">
        <Badge variant="light">Demo page</Badge>
        <Title order={1}>Dummy 2</Title>
        <Text c="dimmed">A second destination for validating links, active navigation and location breadcrumbs.</Text>
      </Stack>
      <Card padding="xl">
        <Stack gap="sm">
          <Title order={2} size="h3">Another feature area</Title>
          <Text c="dimmed">Page content is isolated from the shared shell. The account menu, sidebar and header remain consistent while navigating.</Text>
        </Stack>
      </Card>
    </Stack>
  );
}

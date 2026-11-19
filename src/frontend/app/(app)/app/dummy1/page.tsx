import type { Metadata } from "next";
import { Badge, Card, SimpleGrid, Stack, Text, Title } from "@mantine/core";

export const metadata: Metadata = { title: "Dummy 1" };

export default function DummyOnePage() {
  return (
    <Stack gap="xl">
      <Stack gap="xs">
        <Badge variant="light">Demo page</Badge>
        <Title order={1}>Dummy 1</Title>
        <Text c="dimmed">This placeholder demonstrates a standard feature page within the shared application shell.</Text>
      </Stack>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="lg">
        <Card padding="xl">
          <Title order={2} size="h3">Page content</Title>
          <Text c="dimmed" mt="sm">Future group or expense features can replace this card without changing the navigation layout.</Text>
        </Card>
        <Card padding="xl">
          <Title order={2} size="h3">Responsive by default</Title>
          <Text c="dimmed" mt="sm">The same layout supports a persistent desktop sidebar and a mobile navigation flyout.</Text>
        </Card>
      </SimpleGrid>
    </Stack>
  );
}

import { Button, Card, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { ArrowRightIcon } from "@phosphor-icons/react/ssr";
import Link from "next/link";

export default function WorkspacePage() {
  return (
    <Stack gap="xl">
      <Stack gap="xs">
        <Text size="xs" fw={700} tt="uppercase" c="dimmed">Application demo</Text>
        <Title order={1}>Your workspace.</Title>
        <Text c="dimmed" maw="36rem">An application shell for ShareWise feature development. These routes contain no real account or financial data.</Text>
      </Stack>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="lg">
        <Card padding="xl">
          <Stack align="flex-start" gap="md">
            <Title order={2} size="h3">Dummy 1</Title>
            <Text c="dimmed">A placeholder for an application feature with regular page content.</Text>
            <Link href="/app/dummy1">
              <Button component="span" variant="light" rightSection={<ArrowRightIcon aria-hidden="true" />}>
                Open Dummy 1
              </Button>
            </Link>
          </Stack>
        </Card>
        <Card padding="xl">
          <Stack align="flex-start" gap="md">
            <Title order={2} size="h3">Dummy 2</Title>
            <Text c="dimmed">A second route to exercise navigation, breadcrumbs and responsive layouts.</Text>
            <Link href="/app/dummy2">
              <Button component="span" variant="light" rightSection={<ArrowRightIcon aria-hidden="true" />}>
                Open Dummy 2
              </Button>
            </Link>
          </Stack>
        </Card>
      </SimpleGrid>
    </Stack>
  );
}

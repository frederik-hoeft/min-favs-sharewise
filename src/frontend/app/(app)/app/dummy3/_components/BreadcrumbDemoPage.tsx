import { Badge, Button, Card, Stack, Text, Title } from "@mantine/core";
import { ArrowRightIcon } from "@phosphor-icons/react/ssr";
import Link from "next/link";

type NextLocation = { href: string; label: string };

/** Shared static content for the navigable ancestors of the breadcrumb demo. */
export function BreadcrumbDemoPage({
  title,
  next,
}: {
  title: string;
  next?: NextLocation;
}) {
  return (
    <Stack gap="xl">
      <Stack gap="xs">
        <Badge variant="light">Breadcrumb demo</Badge>
        <Title order={1}>{title}</Title>
        <Text c="dimmed" maw="42rem">
          This is an illustrative route for checking long breadcrumbs in the shared application header.
          Resize the browser or increase zoom to reveal the collapsed trail, then open the ellipsis
          menu to navigate to an ancestor.
        </Text>
      </Stack>
      <Card padding="xl">
        <Stack align="flex-start" gap="md">
          <Title order={2} size="h3">Nested navigation</Title>
          <Text c="dimmed">
            Each ancestor is a real static page, so every link in the breadcrumb menu is navigable.
          </Text>
          {next && (
            <Link href={next.href}>
              <Button component="span" variant="light" rightSection={<ArrowRightIcon aria-hidden="true" />}>
                Continue deeper
              </Button>
            </Link>
          )}
        </Stack>
      </Card>
    </Stack>
  );
}

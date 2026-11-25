import type { Metadata } from "next";
import { Stack, Text, Title } from "@mantine/core";
import { AccountPasswordForm } from "./_components/AccountPasswordForm";
import { AccountProfileForm } from "./_components/AccountProfileForm";

export const metadata: Metadata = { title: "Account" };

export default function AccountPage() {
  return (
    <Stack gap="xl">
      <Stack gap="xs">
        <Text size="xs" fw={700} tt="uppercase" c="dimmed">Account</Text>
        <Title order={1}>Your account</Title>
        <Text c="dimmed">Manage your account details and password.</Text>
      </Stack>
      <AccountProfileForm />
      <AccountPasswordForm />
    </Stack>
  );
}

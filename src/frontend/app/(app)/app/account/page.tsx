import type { Metadata } from "next";
import { Stack, Text, Title } from "@mantine/core";
import { AccountProfileForm } from "./_components/AccountProfileForm";

export const metadata: Metadata = { title: "Account" };

export default function AccountPage() {
  return (
    <Stack gap="xl" maw="42rem">
      <Stack gap="xs">
        <Text size="xs" fw={700} tt="uppercase" c="dimmed">Account</Text>
        <Title order={1}>Your account</Title>
        <Text c="dimmed">View your registered email and manage the name shown on your account.</Text>
      </Stack>
      <AccountProfileForm />
    </Stack>
  );
}

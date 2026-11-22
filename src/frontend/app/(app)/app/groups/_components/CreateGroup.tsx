"use client";

import { Button, Card, Stack, Text, Title } from "@mantine/core";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  classifyGroupProblem,
  normalizedDescription,
  type MetadataInput,
} from "@/core/groups";
import { useGroupsApi } from "./use-groups-api";
import { groupRoutes, groupViewHref } from "../../_components/app-routes";
import {
  clientFieldMessages,
  GroupMetadataForm,
  hasMessages,
  serverFieldMessages,
  type MetadataFieldMessages,
} from "./GroupMetadataForm";

const problemMessages = {
  unauthenticated: "Sign in to create a group.",
  user_unknown: "Your account is not yet available to group management, so it cannot create groups yet.",
  // Creation is not idempotent, and a committed group can still end in a failed response.
  unavailable: "ShareWise could not confirm that the group was created. Check your groups before trying again.",
} as const;

export function CreateGroup() {
  const api = useGroupsApi();
  const router = useRouter();
  const [values, setValues] = useState<MetadataInput>({ name: "", description: "" });
  const [errors, setErrors] = useState<MetadataFieldMessages>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    const clientErrors = clientFieldMessages(values);
    setErrors(clientErrors);
    setFormError(null);
    if (hasMessages(clientErrors)) return;

    setSubmitting(true);
    try {
      const created = await api.create({ name: values.name, description: normalizedDescription(values.description) });
      router.push(groupViewHref(created.id));
    } catch (error) {
      setSubmitting(false);
      const problem = classifyGroupProblem(error);
      if (problem.kind === "invalid") {
        const server = serverFieldMessages(problem.error);
        setErrors(server.fields);
        setFormError(server.formError);
      } else if (problem.kind === "unauthenticated" || problem.kind === "user_unknown") {
        setFormError(problemMessages[problem.kind]);
      } else {
        setFormError(problemMessages.unavailable);
      }
    }
  }

  return (
    <Stack gap="xl" maw="40rem">
      <Stack gap="xs">
        <Title order={1}>Create a group</Title>
        <Text c="dimmed">You become the group&apos;s first member and its administrator.</Text>
      </Stack>
      <Card padding="xl">
        <GroupMetadataForm
          idPrefix="create-group"
          values={values}
          errors={errors}
          formError={formError}
          submitting={submitting}
          submitLabel="Create group"
          onChange={setValues}
          onSubmit={() => void submit()}
          secondaryAction={<Button component={Link} href={groupRoutes.list} variant="subtle">Cancel</Button>}
        />
      </Card>
    </Stack>
  );
}

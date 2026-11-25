"use client";

import { Button, Card, Stack, Text, Title } from "@mantine/core";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import {
  classifyGroupProblem,
  normalizedDescription,
  type MetadataInput,
} from "@/core/groups";
import { useExclusiveOperation } from "@/lib/hooks/use-exclusive-operation";
import { useGroupsApi } from "@/app/use-groups-api";
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

/**
 * Creates a group and opens it. Creation is not idempotent, so a running request locks the form
 * and its Cancel action: neither may suggest that the request can be withdrawn. Leaving the page
 * through workspace navigation abandons only the response, never navigating afterwards; the
 * group may still have been created and then appears in the list.
 */
export function CreateGroup() {
  const api = useGroupsApi();
  const router = useRouter();
  const operation = useExclusiveOperation<"create">();
  const [values, setValues] = useState<MetadataInput>({ name: "", description: "" });
  const [errors, setErrors] = useState<MetadataFieldMessages>({});
  const [formError, setFormError] = useState<string | null>(null);
  // Set once a group was created; the form stays locked until navigation replaces the page.
  const created = useRef(false);
  const [opening, setOpening] = useState(false);

  function submit() {
    if (operation.isRunning() || created.current) return;
    const clientErrors = clientFieldMessages(values);
    setErrors(clientErrors);
    setFormError(null);
    if (hasMessages(clientErrors)) return;

    const request = { name: values.name, description: normalizedDescription(values.description) };
    operation.start("create", () => api.create(request), (outcome) => {
      if (outcome.status === "fulfilled") {
        created.current = true;
        setOpening(true);
        router.push(groupViewHref(outcome.value.id));
        return;
      }
      const problem = classifyGroupProblem(outcome.error);
      if (problem.kind === "invalid") {
        const server = serverFieldMessages(problem.error);
        setErrors(server.fields);
        setFormError(server.formError);
      } else if (problem.kind === "unauthenticated" || problem.kind === "user_unknown") {
        setFormError(problemMessages[problem.kind]);
      } else {
        // Never retried automatically: the group may already exist.
        setFormError(problemMessages.unavailable);
      }
    });
  }

  const busy = operation.pending !== null || opening;
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
          busy={busy}
          submitting={busy}
          submitLabel="Create group"
          onChange={setValues}
          onSubmit={submit}
          secondaryAction={(
            <Button
              component={Link}
              href={groupRoutes.list}
              variant="subtle"
              data-disabled={busy || undefined}
              aria-disabled={busy || undefined}
              onClick={(event) => {
                if (busy) event.preventDefault();
              }}
            >
              Cancel
            </Button>
          )}
        />
      </Card>
    </Stack>
  );
}

"use client";

import { Alert, Button, Card, Stack, Text, Title } from "@mantine/core";
import { ArrowClockwiseIcon } from "@phosphor-icons/react";
import { useState } from "react";
import { classifyGroupProblem, normalizedDescription, type GroupDetails, type GroupProblem, type GroupWorkspace, type MetadataInput } from "@/core/groups";
import { useExclusiveOperation } from "./use-exclusive-operation";
import { useGroupsApi } from "./use-groups-api";
import { clientFieldMessages, GroupMetadataForm, hasMessages, serverFieldMessages, type MetadataFieldMessages } from "./GroupMetadataForm";

interface GroupEditorProps {
  /** The group as the editor opened it; the draft is taken from it once. */
  group: GroupDetails;
  onCancel: () => void;
  onSaved: () => void;
  /** Receives the current details that the user loaded after a conflict. */
  onRefreshed: (workspace: GroupWorkspace) => void;
  onProblem: (problem: GroupProblem) => void;
}

/** Edited metadata together with the version it is based on; the two change only together. */
interface MetadataDraft {
  readonly values: MetadataInput;
  readonly baseVersion: number;
}

function draftOf(group: GroupDetails): MetadataDraft {
  return { values: { name: group.name, description: group.description ?? "" }, baseVersion: group.version };
}

/**
 * Edits a draft bound to the version it was taken from. A save always names that version, never
 * the version the page shows at the time, so it can only replace the state the user edited.
 * Loading the current details replaces the draft and its version together; a conflict keeps the
 * draft until the user explicitly chooses to do so.
 *
 * One operation runs at a time, and its outcome reaches only this editor instance. While a save
 * runs, the draft and Cancel are locked, because closing the editor would hide whether the
 * change was saved. While the current details load, the draft is read-only but Cancel remains
 * available; the abandoned response then changes nothing.
 */
export function GroupEditor({ group, onCancel, onSaved, onRefreshed, onProblem }: GroupEditorProps) {
  const api = useGroupsApi();
  const operation = useExclusiveOperation<"save" | "reload">();
  const [draft, setDraft] = useState<MetadataDraft>(() => draftOf(group));
  const [errors, setErrors] = useState<MetadataFieldMessages>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const groupId = group.id;

  function submit() {
    if (operation.isRunning()) return;
    const clientErrors = clientFieldMessages(draft.values);
    setErrors(clientErrors);
    setFormError(null);
    if (hasMessages(clientErrors)) return;

    const request = {
      name: draft.values.name,
      description: normalizedDescription(draft.values.description),
      expectedVersion: draft.baseVersion,
    };
    operation.start("save", () => api.replaceMetadata(groupId, request), (outcome) => {
      if (outcome.status === "fulfilled") {
        setConflict(false);
        onSaved();
        return;
      }
      const problem = classifyGroupProblem(outcome.error);
      if (problem.kind === "version_conflict") {
        setConflict(true);
      } else if (problem.kind === "invalid") {
        const server = serverFieldMessages(problem.error);
        setErrors(server.fields);
        setFormError(server.formError);
      } else if (problem.kind === "unavailable") {
        // Safe to retry: a replacement that did commit makes the same version conflict.
        setFormError("The save outcome could not be confirmed. Check your connection and try again; an already committed change will be detected as a version conflict.");
      } else {
        onProblem(problem);
      }
    });
  }

  function loadCurrent() {
    operation.start("reload", () => api.getWorkspace(groupId), (outcome) => {
      if (outcome.status === "fulfilled") {
        setDraft(draftOf(outcome.value.group));
        setErrors({});
        setFormError(null);
        setConflict(false);
        onRefreshed(outcome.value);
        return;
      }
      const problem = classifyGroupProblem(outcome.error);
      if (problem.kind === "unavailable") {
        setFormError("The current details could not be loaded. Try again.");
      } else {
        onProblem(problem);
      }
    });
  }

  const pending = operation.pending;
  return (
    <Card padding="xl" component="section" aria-labelledby="edit-group-title">
      <Stack gap="md">
        <Title id="edit-group-title" order={2} size="h3">Edit details</Title>
        {conflict ? (
          <Alert color="yellow" variant="light" role="alert" title="This group was changed in the meantime">
            <Stack gap="sm" align="flex-start">
              <Text size="sm">
                Your changes have not been saved. Loading the current details replaces your input below.
              </Text>
              <Button
                size="xs"
                variant="light"
                leftSection={<ArrowClockwiseIcon aria-hidden="true" />}
                loading={pending === "reload"}
                disabled={pending !== null}
                onClick={loadCurrent}
              >
                Load current details
              </Button>
            </Stack>
          </Alert>
        ) : null}
        <GroupMetadataForm
          idPrefix="edit-group"
          values={draft.values}
          errors={errors}
          formError={formError}
          busy={pending !== null}
          submitting={pending === "save"}
          submitLabel="Save changes"
          onChange={(values) => setDraft((current) => ({ values, baseVersion: current.baseVersion }))}
          onSubmit={submit}
          secondaryAction={<Button variant="subtle" disabled={pending === "save"} onClick={onCancel}>Cancel</Button>}
        />
      </Stack>
    </Card>
  );
}

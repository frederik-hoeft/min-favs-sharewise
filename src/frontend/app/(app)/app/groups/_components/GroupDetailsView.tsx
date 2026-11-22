"use client";

import { Alert, Button, Card, Group, Stack, Text, Title } from "@mantine/core";
import { ArrowClockwiseIcon, PencilSimpleIcon } from "@phosphor-icons/react";
import { useSearchParams } from "next/navigation";
import { useCallback, useState } from "react";
import {
  classifyGroupProblem,
  isGroupId,
  normalizedDescription,
  type GroupDetails,
  type GroupProblem,
  type MetadataInput,
} from "@/core/groups";
import { useGroupsApi } from "./use-groups-api";
import {
  clientFieldMessages,
  GroupMetadataForm,
  hasMessages,
  serverFieldMessages,
  type MetadataFieldMessages,
} from "./GroupMetadataForm";
import { GroupLoadingState, GroupProblemState } from "./GroupProblemState";
import { formatDate, memberCountLabel } from "./group-format";
import { RoleBadge } from "./RoleBadge";
import { useGroupResource } from "./use-group-resource";
import styles from "./GroupDetailsView.module.scss";

/** Reads the group ID from `?id=`; the static export cannot pre-render unknown ID segments. */
export function GroupDetailsView() {
  const groupId = useSearchParams().get("id");
  if (!isGroupId(groupId)) {
    return <GroupProblemState problem={{ kind: "not_found" }} />;
  }
  return <GroupDetailsLoader key={groupId} groupId={groupId} />;
}

function GroupDetailsLoader({ groupId }: { groupId: string }) {
  const api = useGroupsApi();
  const load = useCallback(() => api.get(groupId), [api, groupId]);
  const { state, reload, replace } = useGroupResource(load);
  const [pageProblem, setPageProblem] = useState<GroupProblem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (pageProblem) {
    return <GroupProblemState problem={pageProblem} />;
  }
  if (state.status === "loading") {
    return <GroupLoadingState label="Loading group…" />;
  }
  if (state.status === "failed") {
    return <GroupProblemState problem={state.problem} onRetry={() => void reload()} />;
  }

  async function handleEditorProblem(problem: GroupProblem) {
    if (problem.kind === "admin_required") {
      // A concurrent role change removed editing rights; show the group as it now is.
      setNotice("You are no longer an administrator of this group, so your changes were not saved.");
      await reload();
    } else {
      setPageProblem(problem);
    }
  }

  return (
    <GroupDetailsContent
      group={state.data}
      notice={notice}
      onSaved={(group) => {
        setNotice(null);
        replace(group);
      }}
      onFetchCurrent={async () => {
        try {
          const current = await api.get(groupId);
          replace(current);
          return current;
        } catch (error) {
          const problem = classifyGroupProblem(error);
          if (problem.kind !== "unavailable") setPageProblem(problem);
          return null;
        }
      }}
      onProblem={(problem) => void handleEditorProblem(problem)}
    />
  );
}

interface GroupDetailsContentProps {
  group: GroupDetails;
  notice: string | null;
  onSaved: (group: GroupDetails) => void;
  onFetchCurrent: () => Promise<GroupDetails | null>;
  onProblem: (problem: GroupProblem) => void;
}

function GroupDetailsContent({ group, notice, onSaved, onFetchCurrent, onProblem }: GroupDetailsContentProps) {
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState(false);
  const canEdit = group.callerRole === "admin";

  return (
    <Stack gap="xl" maw="48rem">
      <Stack gap="xs">
        <Group gap="sm" align="center">
          <Title order={1} className={styles.name}>{group.name}</Title>
          <RoleBadge role={group.callerRole} />
        </Group>
        {group.description
          ? <Text c="dimmed" className={styles.description}>{group.description}</Text>
          : <Text c="dimmed" fs="italic">No description</Text>}
      </Stack>
      {notice ? <Alert color="yellow" variant="light" role="status">{notice}</Alert> : null}
      <div role="status" aria-live="polite" className={styles.announcement}>
        {saved ? "Changes saved." : ""}
      </div>
      <Card padding="xl">
        <dl className={styles.facts}>
          <div>
            <dt>Members</dt>
            <dd>{memberCountLabel(group.memberCount)}</dd>
          </div>
          <div>
            <dt>Created</dt>
            <dd><time dateTime={group.createdAt}>{formatDate(group.createdAt)}</time></dd>
          </div>
        </dl>
      </Card>
      {canEdit && !editing ? (
        <Group>
          <Button
            variant="light"
            leftSection={<PencilSimpleIcon aria-hidden="true" />}
            onClick={() => {
              setSaved(false);
              setEditing(true);
            }}
          >
            Edit details
          </Button>
        </Group>
      ) : null}
      {canEdit && editing ? (
        <GroupEditor
          group={group}
          onCancel={() => setEditing(false)}
          onSaved={(updated) => {
            setEditing(false);
            setSaved(true);
            onSaved(updated);
          }}
          onFetchCurrent={onFetchCurrent}
          onProblem={onProblem}
        />
      ) : null}
    </Stack>
  );
}

interface GroupEditorProps {
  group: GroupDetails;
  onCancel: () => void;
  onSaved: (group: GroupDetails) => void;
  onFetchCurrent: () => Promise<GroupDetails | null>;
  onProblem: (problem: GroupProblem) => void;
}

function metadataValues(group: GroupDetails): MetadataInput {
  return { name: group.name, description: group.description ?? "" };
}

/**
 * Edits metadata against the version it was loaded from. A conflict keeps the user's input
 * until they explicitly choose to replace it with the current metadata.
 */
function GroupEditor({ group, onCancel, onSaved, onFetchCurrent, onProblem }: GroupEditorProps) {
  const api = useGroupsApi();
  const [values, setValues] = useState<MetadataInput>(() => metadataValues(group));
  const [errors, setErrors] = useState<MetadataFieldMessages>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reloading, setReloading] = useState(false);

  async function submit() {
    const clientErrors = clientFieldMessages(values);
    setErrors(clientErrors);
    setFormError(null);
    if (hasMessages(clientErrors)) return;

    setSubmitting(true);
    try {
      const updated = await api.replaceMetadata(group.id, {
        name: values.name,
        description: normalizedDescription(values.description),
        expectedVersion: group.version,
      });
      setConflict(false);
      onSaved(updated);
    } catch (error) {
      const problem = classifyGroupProblem(error);
      if (problem.kind === "version_conflict") {
        setConflict(true);
      } else if (problem.kind === "invalid") {
        const server = serverFieldMessages(problem.error);
        setErrors(server.fields);
        setFormError(server.formError);
      } else if (problem.kind === "unavailable") {
        // Safe to retry: a replacement that did commit makes the same version conflict.
        setFormError("Your changes could not be saved. Check your connection and try again.");
      } else {
        onProblem(problem);
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function loadCurrent() {
    setReloading(true);
    const current = await onFetchCurrent();
    setReloading(false);
    if (current) {
      setValues(metadataValues(current));
      setErrors({});
      setFormError(null);
      setConflict(false);
    } else {
      setFormError("The current details could not be loaded. Try again.");
    }
  }

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
                loading={reloading}
                onClick={() => void loadCurrent()}
              >
                Load current details
              </Button>
            </Stack>
          </Alert>
        ) : null}
        <GroupMetadataForm
          idPrefix="edit-group"
          values={values}
          errors={errors}
          formError={formError}
          submitting={submitting}
          submitLabel="Save changes"
          onChange={setValues}
          onSubmit={() => void submit()}
          secondaryAction={<Button variant="subtle" onClick={onCancel}>Cancel</Button>}
        />
      </Stack>
    </Card>
  );
}

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
import { useExclusiveOperation } from "./use-exclusive-operation";
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
      onRefreshed={replace}
      onProblem={(problem) => void handleEditorProblem(problem)}
    />
  );
}

interface GroupDetailsContentProps {
  group: GroupDetails;
  notice: string | null;
  onSaved: (group: GroupDetails) => void;
  onRefreshed: (group: GroupDetails) => void;
  onProblem: (problem: GroupProblem) => void;
}

function GroupDetailsContent({ group, notice, onSaved, onRefreshed, onProblem }: GroupDetailsContentProps) {
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
          onRefreshed={onRefreshed}
          onProblem={onProblem}
        />
      ) : null}
    </Stack>
  );
}

interface GroupEditorProps {
  /** The group as the editor opened it; the draft is taken from it once. */
  group: GroupDetails;
  onCancel: () => void;
  onSaved: (group: GroupDetails) => void;
  /** Receives the current details that the user loaded after a conflict. */
  onRefreshed: (group: GroupDetails) => void;
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
function GroupEditor({ group, onCancel, onSaved, onRefreshed, onProblem }: GroupEditorProps) {
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
        onSaved(outcome.value);
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
        setFormError("Your changes could not be saved. Check your connection and try again.");
      } else {
        onProblem(problem);
      }
    });
  }

  function loadCurrent() {
    operation.start("reload", () => api.get(groupId), (outcome) => {
      if (outcome.status === "fulfilled") {
        setDraft(draftOf(outcome.value));
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

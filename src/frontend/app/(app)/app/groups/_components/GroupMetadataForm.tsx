"use client";

import { Alert, Button, Group, Stack, Textarea, TextInput } from "@mantine/core";
import { WarningCircleIcon } from "@phosphor-icons/react";
import type { FormEvent, ReactNode } from "react";
import {
  GROUP_DESCRIPTION_MAX_CODE_POINTS,
  GROUP_NAME_MAX_CODE_POINTS,
  codePointCount,
  validateGroupMetadata,
  type MetadataField,
  type MetadataInput,
  type MetadataViolation,
} from "@/core/groups";
import type { ApiError } from "@/lib/api";

/** Field messages: client-side violations are translated, server messages are shown as sent. */
export type MetadataFieldMessages = Partial<Record<MetadataField, string>>;

const violationMessages: Record<MetadataField, Record<MetadataViolation, string>> = {
  name: {
    required: "Enter a group name.",
    too_long: `Use at most ${GROUP_NAME_MAX_CODE_POINTS} characters.`,
    control_character: "Remove tabs, line breaks and other control characters.",
  },
  description: {
    required: "Enter a description.",
    too_long: `Use at most ${GROUP_DESCRIPTION_MAX_CODE_POINTS} characters.`,
    control_character: "Line breaks are allowed; remove tabs and other control characters.",
  },
};

/** Client-side validation of both fields, as display messages. */
export function clientFieldMessages(values: MetadataInput): MetadataFieldMessages {
  const violations = validateGroupMetadata(values);
  const messages: MetadataFieldMessages = {};
  for (const field of ["name", "description"] as const) {
    const violation = violations[field];
    if (violation) messages[field] = violationMessages[field][violation];
  }
  return messages;
}

/**
 * Splits a server `400` into messages on the metadata fields and a form-level message for
 * anything that cannot be attributed to a field.
 */
export function serverFieldMessages(error: ApiError): { fields: MetadataFieldMessages; formError: string | null } {
  const fields: MetadataFieldMessages = {};
  let unattributed = false;
  for (const fieldError of error.fieldErrors) {
    if (fieldError.field === "name" || fieldError.field === "description") {
      fields[fieldError.field] ??= fieldError.message;
    } else {
      unattributed = true;
    }
  }
  const attributed = Object.keys(fields).length > 0;
  const formError = unattributed || !attributed ? "The request was rejected. Check the details and try again." : null;
  return { fields, formError };
}

export function hasMessages(messages: MetadataFieldMessages): boolean {
  return Object.keys(messages).length > 0;
}

interface GroupMetadataFormProps {
  idPrefix: string;
  values: MetadataInput;
  errors: MetadataFieldMessages;
  formError?: ReactNode;
  /** An operation is running: the fields are read-only and the submit action is unavailable. */
  busy: boolean;
  /** The running operation is this form's submission, which the submit button shows as loading. */
  submitting: boolean;
  submitLabel: string;
  onChange: (values: MetadataInput) => void;
  onSubmit: () => void;
  secondaryAction?: ReactNode;
}

/**
 * Controlled name and description fields shared by group creation and editing. While `busy`,
 * the fields stay focusable but read-only, so input cannot change after a request has captured
 * it. The owner still guards `onSubmit` itself, because a submit event can arrive before the
 * busy state has rendered.
 */
export function GroupMetadataForm({
  idPrefix,
  values,
  errors,
  formError,
  busy,
  submitting,
  submitLabel,
  onChange,
  onSubmit,
  secondaryAction,
}: GroupMetadataFormProps) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit();
  }

  return (
    <form noValidate onSubmit={handleSubmit} aria-busy={busy}>
      <Stack gap="md">
        {formError ? (
          <Alert color="red" variant="light" icon={<WarningCircleIcon aria-hidden="true" />} role="alert">
            {formError}
          </Alert>
        ) : null}
        <TextInput
          id={`${idPrefix}-name`}
          label="Name"
          required
          autoComplete="off"
          value={values.name}
          readOnly={busy}
          error={errors.name}
          onChange={(event) => onChange({ ...values, name: event.currentTarget.value })}
        />
        <Textarea
          id={`${idPrefix}-description`}
          label="Description"
          description={`Optional. ${codePointCount(values.description)} of ${GROUP_DESCRIPTION_MAX_CODE_POINTS} characters.`}
          autosize
          minRows={3}
          maxRows={8}
          value={values.description}
          readOnly={busy}
          error={errors.description}
          onChange={(event) => onChange({ ...values, description: event.currentTarget.value })}
        />
        <Group gap="sm">
          <Button type="submit" loading={submitting} disabled={busy}>{submitLabel}</Button>
          {secondaryAction}
        </Group>
      </Stack>
    </form>
  );
}

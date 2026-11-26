"use client";

import { Button, Group, Modal, Stack } from "@mantine/core";
import type { ReactNode } from "react";

interface ConfirmationDialogProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  pending: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Explicit confirmation for a mutation. Once submitted, dismissal cannot conceal its outcome. */
export function ConfirmationDialog({ title, children, confirmLabel, danger, pending, onConfirm, onCancel }: ConfirmationDialogProps) {
  return (
    <Modal
      opened
      onClose={() => { if (!pending) onCancel(); }}
      title={title}
      centered
      withCloseButton={!pending}
      returnFocus={false} // The owning action restores its actual trigger, including a dismissed Drawer.
      closeOnClickOutside={!pending}
      closeOnEscape={!pending}
      closeButtonProps={{ "aria-label": "Cancel confirmation" }}
    >
      <Stack gap="lg" aria-busy={pending}>
        {children}
        <Group justify="flex-end">
          <Button variant="default" data-autofocus disabled={pending} onClick={onCancel}>Cancel</Button>
          <Button color={danger ? "red" : undefined} loading={pending} disabled={pending} onClick={onConfirm}>{confirmLabel}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

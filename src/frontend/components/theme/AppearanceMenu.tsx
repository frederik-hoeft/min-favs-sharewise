"use client";

import { ActionIcon, Menu } from "@mantine/core";
import { CircleHalfIcon } from "@phosphor-icons/react";
import { AppearanceMenuItems } from "./AppearanceMenuItems";

/** Standalone appearance selector for the public site. */
export function AppearanceMenu() {
  return (
    <Menu position="bottom-end" width="10.25rem" withinPortal>
      <Menu.Target>
        <ActionIcon variant="default" size="lg" radius="xl" aria-label="Choose appearance" title="Appearance">
          <CircleHalfIcon size="1.1875rem" aria-hidden="true" />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown aria-label="Appearance">
        <AppearanceMenuItems />
      </Menu.Dropdown>
    </Menu>
  );
}

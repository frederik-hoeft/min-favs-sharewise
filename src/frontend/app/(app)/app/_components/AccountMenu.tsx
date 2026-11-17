"use client";

import { Avatar, Menu, UnstyledButton } from "@mantine/core";
import { UserCircleIcon } from "@phosphor-icons/react";
import { AppearanceMenuItems } from "@/components/theme/AppearanceMenuItems";
import styles from "./AccountMenu.module.scss";

/** Placeholder account control; authentication and account actions are not implemented. */
export function AccountMenu() {
  return (
    <Menu position="bottom-end" width="11rem" withinPortal>
      <Menu.Target>
        <UnstyledButton className={styles.trigger} aria-label="Account and appearance" title="Account and appearance">
          <Avatar color="indigoBrand" variant="light" radius="xl" size="md">
            <UserCircleIcon size="1.5rem" aria-hidden="true" />
          </Avatar>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown aria-label="Account menu">
        <AppearanceMenuItems />
      </Menu.Dropdown>
    </Menu>
  );
}

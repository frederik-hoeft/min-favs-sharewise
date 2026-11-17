"use client";

import { Avatar, Menu, UnstyledButton } from "@mantine/core";
import { CaretLeftIcon, CaretRightIcon, CircleHalfIcon, UserCircleIcon } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import { AppearanceMenuItems } from "@/components/theme/AppearanceMenuItems";
import styles from "./AccountMenu.module.scss";

/** A two-level Mantine menu; the secondary view fits narrow/mobile viewports. */
export function AccountMenu() {
  const [opened, setOpened] = useState(false);
  const [screen, setScreen] = useState<"account" | "appearance">("account");
  const appearanceRef = useRef<HTMLButtonElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);

  // Changing in-menu views removes the focused item. Restore keyboard focus to
  // the newly displayed navigation item, including after returning from Appearance.
  useEffect(() => {
    if (opened) (screen === "account" ? appearanceRef : backRef).current?.focus();
  }, [opened, screen]);

  function updateOpened(nextOpened: boolean) {
    setOpened(nextOpened);
    if (!nextOpened) setScreen("account");
  }

  return (
    <Menu
      opened={opened}
      onChange={updateOpened}
      closeOnItemClick={false}
      position="bottom-end"
      width="12.5rem"
      withinPortal
      // The fixed header target is never scrolled out; changing subviews should not detach the dropdown.
      hideDetached={false}
    >
      <Menu.Target>
        <UnstyledButton className={styles.trigger} aria-label="Account menu" title="Account menu">
          <Avatar color="indigoBrand" variant="light" radius="xl" size="md">
            <UserCircleIcon size="1.5rem" aria-hidden="true" />
          </Avatar>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown aria-label={screen === "account" ? "Account menu" : "Appearance menu"}>
        {screen === "account" ? (
          <>
            <Menu.Label>Account</Menu.Label>
            <Menu.Item
              ref={appearanceRef}
              leftSection={<CircleHalfIcon size="1.125rem" aria-hidden="true" />}
              rightSection={<CaretRightIcon size="0.875rem" aria-hidden="true" />}
              onClick={() => setScreen("appearance")}
            >
              Appearance
            </Menu.Item>
          </>
        ) : (
          <>
            <Menu.Item
              ref={backRef}
              leftSection={<CaretLeftIcon size="0.875rem" aria-hidden="true" />}
              onClick={() => setScreen("account")}
            >
              Back to account
            </Menu.Item>
            <Menu.Divider />
            <AppearanceMenuItems onSelect={() => updateOpened(false)} />
          </>
        )}
      </Menu.Dropdown>
    </Menu>
  );
}

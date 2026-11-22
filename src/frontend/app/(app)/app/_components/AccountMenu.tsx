"use client";

import { ActionIcon, Menu } from "@mantine/core";
import { CaretLeftIcon, CaretRightIcon, CircleHalfIcon, SignOutIcon, UserCircleIcon } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useIdentity, useAuthState } from "@/app/identity-provider";
import { AppearanceMenuItems } from "@/components/theme/AppearanceMenuItems";

/** A two-level Mantine menu; the secondary view fits narrow/mobile viewports. */
export function AccountMenu() {
  const [opened, setOpened] = useState(false);
  const { auth } = useIdentity();
  const state = useAuthState();
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);
  const [signOutError, setSignOutError] = useState(false);
  const [screen, setScreen] = useState<"account" | "appearance">("account");
  const appearanceRef = useRef<HTMLButtonElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);
  const restoreKeyboardFocus = useRef(false);

  // Replacing the menu's contents removes the current item. Restore focus
  // only after keyboard activation: pointer activation must not leave the
  // new top item permanently highlighted by an artificial focus state.
  useEffect(() => {
    if (opened && restoreKeyboardFocus.current) {
      (screen === "account" ? appearanceRef : backRef).current?.focus();
      restoreKeyboardFocus.current = false;
    }
  }, [opened, screen]);

  function changeScreen(nextScreen: "account" | "appearance", keyboardActivated: boolean) {
    restoreKeyboardFocus.current = keyboardActivated;
    setScreen(nextScreen);
  }

  function updateOpened(nextOpened: boolean) {
    setOpened(nextOpened);
    if (!nextOpened) {
      restoreKeyboardFocus.current = false;
      setScreen("account");
    }
  }

  async function signOut() {
    if (signingOut) return;
    setSigningOut(true);
    setSignOutError(false);
    try {
      await auth.logout();
      updateOpened(false);
      router.replace("/auth/login");
    } catch {
      setSignOutError(true);
    } finally {
      setSigningOut(false);
    }
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
        <ActionIcon variant="subtle" color="gray" radius="xl" size="lg" aria-label="Account menu" title="Account menu">
          <UserCircleIcon size="1.5rem" aria-hidden="true" />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown aria-label={screen === "account" ? "Account menu" : "Appearance menu"}>
        {screen === "account" ? (
          <>
            <Menu.Label>Account</Menu.Label>
            <Menu.Item
              ref={appearanceRef}
              leftSection={<CircleHalfIcon size="1.125rem" aria-hidden="true" />}
              rightSection={<CaretRightIcon size="0.875rem" aria-hidden="true" />}
              onClick={(event) => changeScreen("appearance", event.detail === 0)}
            >
              Appearance
            </Menu.Item>
            {state.status === "authenticated" && (
              <Menu.Item
                leftSection={<SignOutIcon size="1.125rem" aria-hidden="true" />}
                disabled={signingOut}
                onClick={() => void signOut()}
              >
                {signingOut ? "Signing out…" : "Sign out"}
              </Menu.Item>
            )}
            {signOutError && <Menu.Label>Could not confirm sign-out. Try again or sign in anew.</Menu.Label>}
          </>
        ) : (
          <>
            <Menu.Item
              ref={backRef}
              leftSection={<CaretLeftIcon size="0.875rem" aria-hidden="true" />}
              onClick={(event) => changeScreen("account", event.detail === 0)}
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

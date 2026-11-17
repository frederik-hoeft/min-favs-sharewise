"use client";

import { ActionIcon, Menu, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";
import { CheckIcon, CircleHalfIcon } from "@phosphor-icons/react";

const choices: { value: MantineColorScheme; label: string }[] = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "auto", label: "System" },
];

/** The only interactive island used by the public landing page. */
export function AppearanceMenu() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <Menu position="bottom-end" width="10.25rem" withinPortal>
      <Menu.Target>
        <ActionIcon variant="default" size="lg" radius="xl" aria-label="Choose appearance" title="Appearance">
          <CircleHalfIcon size="1.1875rem" aria-hidden="true" />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown aria-label="Appearance">
        <Menu.Label>Appearance</Menu.Label>
        {choices.map(({ value, label }) => (
          <Menu.Item
            key={value}
            onClick={() => setColorScheme(value)}
            rightSection={colorScheme === value ? <CheckIcon size="0.875rem" weight="bold" aria-hidden="true" /> : null}
            aria-label={`${label} theme${colorScheme === value ? ", selected" : ""}`}
          >
            {label}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

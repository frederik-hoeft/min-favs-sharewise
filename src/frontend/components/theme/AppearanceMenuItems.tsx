"use client";

import { Menu, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";
import { CheckIcon } from "@phosphor-icons/react";

const choices: { value: MantineColorScheme; label: string }[] = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "auto", label: "System" },
];

/** Appearance options shared by the public selector and the application account menu. */
export function AppearanceMenuItems({ onSelect }: { onSelect?: () => void }) {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <>
      <Menu.Label>Appearance</Menu.Label>
      {choices.map(({ value, label }) => (
        <Menu.Item
          key={value}
          onClick={() => { setColorScheme(value); onSelect?.(); }}
          rightSection={colorScheme === value ? <CheckIcon size="0.875rem" weight="bold" aria-hidden="true" /> : null}
          aria-label={`${label} theme${colorScheme === value ? ", selected" : ""}`}
        >
          {label}
        </Menu.Item>
      ))}
    </>
  );
}

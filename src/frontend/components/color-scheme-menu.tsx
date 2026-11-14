"use client";

import { ActionIcon, Menu, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";

const choices: { value: MantineColorScheme; label: string }[] = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "auto", label: "System" },
];

/** Single appearance control for both public and future authenticated shells. */
export function ColorSchemeMenu() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const selection = choices.find(({ value }) => value === colorScheme)?.label ?? "System";

  return (
    <Menu position="bottom-end" withinPortal shadow="md" width={176}>
      <Menu.Target>
        <ActionIcon
          variant="subtle"
          color="slate"
          size="lg"
          aria-label={`Appearance: ${selection}. Choose color theme`}
        >
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2m0 16v2M4.93 4.93l1.41 1.41m11.32 11.32 1.41 1.41M2 12h2m16 0h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
          </svg>
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>Color theme</Menu.Label>
        {choices.map(({ value, label }) => (
          <Menu.Item
            key={value}
            aria-label={colorScheme === value ? `${label} (selected)` : label}
            onClick={() => setColorScheme(value)}
            rightSection={colorScheme === value ? <span aria-hidden="true">✓</span> : undefined}
          >
            {label}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

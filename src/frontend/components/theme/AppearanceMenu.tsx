"use client";

import { ActionIcon, Menu, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";

const choices: { value: MantineColorScheme; label: string }[] = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "auto", label: "System" },
];

/** The only interactive island used by the public landing page. */
export function AppearanceMenu() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <Menu position="bottom-end" width={164} withinPortal>
      <Menu.Target>
        <ActionIcon variant="default" size="lg" radius="xl" aria-label="Choose appearance" title="Appearance">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M12 3a9 9 0 1 0 0 18V3Z" /><circle cx="12" cy="12" r="9" />
          </svg>
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown aria-label="Appearance">
        <Menu.Label>Appearance</Menu.Label>
        {choices.map(({ value, label }) => (
          <Menu.Item
            key={value}
            onClick={() => setColorScheme(value)}
            rightSection={colorScheme === value ? <span aria-hidden="true">✓</span> : null}
            aria-label={`${label} theme${colorScheme === value ? ", selected" : ""}`}
          >
            {label}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

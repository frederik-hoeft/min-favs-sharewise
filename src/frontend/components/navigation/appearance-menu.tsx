"use client";

import { Button, Menu, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";

const modes: ReadonlyArray<{ value: MantineColorScheme; label: string }> = [
  { value: "auto", label: "System preference" },
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
];

/** A three-way choice; labels do not depend on browser-only stored state during SSR. */
export function AppearanceMenu() {
  const { setColorScheme } = useMantineColorScheme();

  return (
    <Menu position="bottom-end" width={196} shadow="md">
      <Menu.Target>
        <Button variant="subtle" color="pine" size="sm" aria-label="Appearance settings">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
            <circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42" />
          </svg>
          <span>Appearance</span>
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>Color scheme</Menu.Label>
        {modes.map((mode) => (
          <Menu.Item key={mode.value} onClick={() => setColorScheme(mode.value)}>
            {mode.label}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

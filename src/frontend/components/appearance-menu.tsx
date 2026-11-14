"use client";

import { ActionIcon, Menu, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";

const options: { value: MantineColorScheme; label: string; symbol: string }[] = [
  { value: "auto", label: "System", symbol: "◐" },
  { value: "light", label: "Light", symbol: "☀" },
  { value: "dark", label: "Dark", symbol: "☾" },
];

export function AppearanceMenu() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <Menu position="bottom-end" withinPortal>
      <Menu.Target>
        <ActionIcon aria-label="Appearance settings" size="lg" color="pine" variant="default">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
            <circle cx="12" cy="12" r="8.5" />
            <path d="M12 3.5a8.5 8.5 0 0 1 0 17Z" fill="currentColor" stroke="none" />
          </svg>
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>Appearance</Menu.Label>
        {options.map(({ value, label, symbol }) => (
          <Menu.Item key={value} onClick={() => setColorScheme(value)}
            leftSection={<span aria-hidden="true">{symbol}</span>}
            rightSection={colorScheme === value ? <span aria-hidden="true">✓</span> : null}
            aria-current={colorScheme === value ? "true" : undefined}>
            {label}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

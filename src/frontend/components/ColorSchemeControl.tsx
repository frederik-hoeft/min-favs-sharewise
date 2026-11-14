"use client";

import { ActionIcon, Menu, useMantineColorScheme } from "@mantine/core";
import { useMounted } from "@mantine/hooks";

type Scheme = "light" | "dark" | "auto";
const schemes: { value: Scheme; label: string; symbol: string }[] = [
  { value: "light", label: "Light", symbol: "☀" },
  { value: "dark", label: "Dark", symbol: "☾" },
  { value: "auto", label: "System", symbol: "◐" },
];

export function ColorSchemeControl() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  // Mantine's initial server render cannot know the stored browser preference.
  const mounted = useMounted();

  return (
    <Menu position="bottom-end" width={174} withinPortal>
      <Menu.Target>
        <ActionIcon aria-label="Change color theme" title="Change color theme" size="lg" color="gray">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="9" /><path d="M12 3a9 9 0 0 0 0 18V3Z" />
          </svg>
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown aria-label="Color theme">
        <Menu.Label>APPEARANCE</Menu.Label>
        <Menu.RadioGroup
          value={mounted ? colorScheme : "auto"}
          onChange={(value) => setColorScheme(value as Scheme)}
        >
          {schemes.map(({ value, label, symbol }) => (
            <Menu.RadioItem key={value} value={value} closeMenuOnClick>
              <span aria-hidden="true">{symbol} </span>{label}
            </Menu.RadioItem>
          ))}
        </Menu.RadioGroup>
      </Menu.Dropdown>
    </Menu>
  );
}

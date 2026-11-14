"use client";

import { NativeSelect, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";

/** Mantine owns preference resolution, persistence, and OS preference changes. */
export function ThemePicker() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <NativeSelect
      aria-label="Appearance"
      value={colorScheme}
      onChange={(event) => setColorScheme(event.currentTarget.value as MantineColorScheme)}
      data={[
        { label: "System", value: "auto" },
        { label: "Light", value: "light" },
        { label: "Dark", value: "dark" },
      ]}
    />
  );
}

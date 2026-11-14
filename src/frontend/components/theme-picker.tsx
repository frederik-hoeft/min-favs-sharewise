"use client";

import { SegmentedControl, useMantineColorScheme } from "@mantine/core";

/** Uses Mantine's own persistent, system-aware color-scheme manager. */
export function ThemePicker() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <SegmentedControl
      aria-label="Color theme"
      size="xs"
      value={colorScheme}
      onChange={(value) => setColorScheme(value as "light" | "dark" | "auto")}
      data={[
        { label: "Auto", value: "auto" },
        { label: "Light", value: "light" },
        { label: "Dark", value: "dark" },
      ]}
    />
  );
}

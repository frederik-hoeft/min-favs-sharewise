"use client";

import { SegmentedControl, useMantineColorScheme, type MantineColorScheme } from "@mantine/core";
import styles from "./theme-control.module.scss";

/** Native Mantine radio segments, with system preference explicitly selectable. */
export function ThemeControl() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();

  return (
    <SegmentedControl
      className={styles.control}
      size="xs"
      radius="xs"
      aria-label="Color scheme"
      value={colorScheme}
      onChange={(value) => setColorScheme(value as MantineColorScheme)}
      data={[
        { value: "auto", label: "Auto" },
        { value: "light", label: "Light" },
        { value: "dark", label: "Dark" },
      ]}
    />
  );
}

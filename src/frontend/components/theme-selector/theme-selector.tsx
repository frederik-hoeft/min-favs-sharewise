"use client";

import { NativeSelect, useMantineColorScheme } from "@mantine/core";
import type { MantineColorScheme } from "@mantine/core";
import styles from "./theme-selector.module.scss";

/** Native keyboard/select semantics with Mantine-managed persistence and system preference. */
export function ThemeSelector() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  return (
    <NativeSelect
      aria-label="Appearance"
      className={styles.selector}
      data={[
        { label: "System", value: "auto" },
        { label: "Light", value: "light" },
        { label: "Dark", value: "dark" },
      ]}
      value={colorScheme}
      onChange={(event) => setColorScheme(event.currentTarget.value as MantineColorScheme)}
      size="sm"
      radius="xl"
      leftSection={<span aria-hidden="true" className={styles.icon}>◐</span>}
    />
  );
}

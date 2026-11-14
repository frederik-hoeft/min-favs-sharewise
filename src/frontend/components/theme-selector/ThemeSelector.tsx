"use client";

import { SegmentedControl, useMantineColorScheme } from "@mantine/core";
import { useMounted } from "@mantine/hooks";
import styles from "./ThemeSelector.module.scss";

/** Persisted light/system/dark preference, using Mantine's color-scheme manager. */
export function ThemeSelector() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const mounted = useMounted();

  // On the server, persisted browser preferences are unknown. Render an identical
  // initial selection during hydration, then reflect the stored value.
  return (
    <SegmentedControl
      className={styles.selector}
      aria-label="Color theme"
      value={mounted ? colorScheme : "auto"}
      onChange={(value) => {
        if (value === "light" || value === "dark" || value === "auto") {
          setColorScheme(value);
        }
      }}
      data={[
        { label: "Light", value: "light" },
        { label: "System", value: "auto" },
        { label: "Dark", value: "dark" },
      ]}
    />
  );
}

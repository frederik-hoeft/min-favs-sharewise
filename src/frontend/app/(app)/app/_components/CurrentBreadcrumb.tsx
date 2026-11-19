"use client";

import { Popover, Text, UnstyledButton } from "@mantine/core";
import { useEffect, useRef, useState } from "react";
import styles from "./CurrentBreadcrumb.module.scss";

type Props = {
  label: string;
};

/**
 * The current location stays on one line within the fixed-height header.
 * Its measured text remains in the same layout position whether or not a
 * full-label disclosure is available, so opening the disclosure cannot change
 * the measurement that determines its visibility.
 */
export function CurrentBreadcrumb({ label }: Props) {
  const textRef = useRef<HTMLParagraphElement>(null);
  const [truncated, setTruncated] = useState(false);

  useEffect(() => {
    const text = textRef.current;
    if (!text) return;

    const update = () => setTruncated(text.scrollWidth > text.clientWidth + 1);
    update();

    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(update);
    observer.observe(text);
    return () => observer.disconnect();
  }, [label]);

  return (
    <div className={`${styles.current} ${truncated ? styles.interactive : ""}`}>
      <Text span size="sm" truncate="end" ref={textRef} aria-current="page" className={styles.label} title={label}>
        {label}
      </Text>
      {truncated && (
        <Popover position="bottom-end" withinPortal shadow="sm">
          <Popover.Target>
            <UnstyledButton
              type="button"
              className={styles.trigger}
              aria-label={`Show full location: ${label}`}
              title={label}
            />
          </Popover.Target>
          <Popover.Dropdown className={styles.dropdown} aria-label="Current breadcrumb location">
            <Text size="xs" c="dimmed" fw={600}>Current location</Text>
            <Text size="sm" className={styles.fullLabel}>{label}</Text>
          </Popover.Dropdown>
        </Popover>
      )}
    </div>
  );
}

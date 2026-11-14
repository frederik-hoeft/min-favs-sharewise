"use client";

import { Burger, Drawer, Stack } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import styles from "./landing-header.module.scss";
import { landingLinks } from "../_data/navigation";

export function MobileNavigation() {
  const [opened, { toggle, close }] = useDisclosure(false);

  return (
    <div className={styles.mobileNavigation}>
      <Burger
        opened={opened}
        onClick={toggle}
        aria-label={opened ? "Close navigation" : "Open navigation"}
        size="sm"
      />
      <Drawer
        opened={opened}
        onClose={close}
        title="Navigate ShareWise"
        position="right"
        size="xs"
        padding="lg"
      >
        <nav aria-label="Mobile primary navigation">
          <Stack gap="xs">
            {landingLinks.map(({ label, href }) => (
              <a className={styles.drawerLink} href={href} key={href} onClick={close}>
                {label}
              </a>
            ))}
          </Stack>
        </nav>
      </Drawer>
    </div>
  );
}

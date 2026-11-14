"use client";

import { Burger, Drawer } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { siteNavigation } from "./navigation";
import styles from "./site-header.module.scss";

export function MobileNavigation() {
  const [opened, { open, close }] = useDisclosure(false);

  return (
    <div className={styles.mobileNavigation}>
      <Burger opened={opened} onClick={opened ? close : open} aria-label={opened ? "Close menu" : "Open menu"} size="sm" />
      <Drawer opened={opened} onClose={close} title="Explore ShareWise" position="right" size="xs">
        <nav className={styles.drawerLinks} aria-label="Mobile navigation">
          {siteNavigation.map((link) => (
            <a key={link.href} href={link.href} onClick={close}>{link.label}</a>
          ))}
        </nav>
      </Drawer>
    </div>
  );
}

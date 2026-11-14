"use client";

import { ActionIcon, Menu } from "@mantine/core";
import styles from "./SiteHeader.module.scss";

const links = [
  { href: "#platform", label: "Platform" },
  { href: "#how-it-works", label: "How it works" },
  { href: "#principles", label: "Our approach" },
];

export function MobileNavigation() {
  return (
    <Menu position="bottom-end" width={205} withinPortal>
      <Menu.Target>
        <ActionIcon className={styles.mobileMenu} size="lg" variant="subtle" color="gray" aria-label="Open navigation">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
            <path d="M4 7h16M4 12h16M4 17h16" />
          </svg>
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown aria-label="Mobile navigation">
        {links.map((link) => (
          <Menu.Item key={link.href} component="a" href={link.href}>{link.label}</Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

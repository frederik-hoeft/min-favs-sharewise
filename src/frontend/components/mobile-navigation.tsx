"use client";

import { Button, Menu } from "@mantine/core";
import { navigation } from "./navigation";

export function MobileNavigation() {
  return (
    <Menu position="bottom-end" withinPortal>
      <Menu.Target>
        <Button variant="subtle" color="pine" size="sm" aria-label="Open site navigation" rightSection={<span aria-hidden="true">☰</span>}>
          Menu
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {navigation.map(({ href, label }) => (
          <Menu.Item component="a" href={href} key={href}>{label}</Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

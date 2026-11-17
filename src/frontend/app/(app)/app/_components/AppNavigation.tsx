"use client";

import { NavLink, Stack } from "@mantine/core";
import { HouseIcon, SquaresFourIcon } from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { appRoutes } from "./app-routes";
import styles from "./AppNavigation.module.scss";

const icons = {
  "/app/dummy1": HouseIcon,
  "/app/dummy2": SquaresFourIcon,
} satisfies Record<(typeof appRoutes)[number]["href"], typeof HouseIcon>;

/** The same navigation is rendered in the desktop navbar and the mobile drawer. */
export function AppNavigation({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();

  return (
    <Stack gap="xs">
      {appRoutes.map(({ href, label }) => {
        const Icon = icons[href];
        const active = pathname === href || pathname.startsWith(`${href}/`);
        return (
          <NavLink
            key={href}
            component={Link}
            href={href}
            label={label}
            leftSection={<Icon size="1.125rem" aria-hidden="true" />}
            active={active}
            aria-current={active ? "page" : undefined}
            onClick={onNavigate}
            variant="light"
            className={styles.link}
          />
        );
      })}
    </Stack>
  );
}

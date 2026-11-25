"use client";

import { NavLink, Stack } from "@mantine/core";
import { UsersThreeIcon } from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { appNavigationRoutes } from "./app-routes";
import styles from "./AppNavigation.module.scss";

/** Shared links for expanded nav, focus/hover rail and mobile drawer. */
export function AppNavigation({ onNavigate, rail = false }: { onNavigate?: () => void; rail?: boolean }) {
  const pathname = usePathname();

  return (
    <Stack gap="xs">
      {appNavigationRoutes.map(({ href, label }) => {
        const active = pathname === href || pathname.startsWith(`${href}/`);
        return (
          <NavLink
            key={href}
            component={Link}
            href={href}
            label={label}
            leftSection={<UsersThreeIcon size="1.25rem" aria-hidden="true" />}
            active={active}
            aria-label={label}
            aria-current={active ? "page" : undefined}
            onClick={onNavigate}
            classNames={rail ? { root: styles.railLink, section: styles.railSection, body: styles.railBody } : undefined}
          />
        );
      })}
    </Stack>
  );
}

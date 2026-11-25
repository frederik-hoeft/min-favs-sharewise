"use client";

import { Burger } from "@mantine/core";
import { usePathname } from "next/navigation";
import { AccountMenu } from "./AccountMenu";
import type { ReactNode } from "react";
import { appBreadcrumbs, type AppBreadcrumb } from "./app-routes";
import styles from "./ApplicationHeader.module.scss";
import { ResponsiveBreadcrumbs } from "./ResponsiveBreadcrumbs";

export function ApplicationHeader({ opened, toggleNavigation, breadcrumbs, asideControl }: { opened: boolean; toggleNavigation: () => void; breadcrumbs?: AppBreadcrumb[]; asideControl?: ReactNode }) {
  const pathname = usePathname();
  const crumbs = breadcrumbs ?? appBreadcrumbs(pathname);

  return (
    <div className={styles.content}>
      <Burger
        className={styles.mobileToggle}
        opened={opened}
        onClick={toggleNavigation}
        size="sm"
        aria-label={opened ? "Close navigation" : "Open navigation"}
        aria-expanded={opened}
        aria-controls="mobile-app-navigation"
        title={opened ? "Close navigation" : "Open navigation"}
      />
      <ResponsiveBreadcrumbs key={pathname} crumbs={crumbs} />
      <div className={styles.account}>
        {asideControl}
        <AccountMenu />
      </div>
    </div>
  );
}

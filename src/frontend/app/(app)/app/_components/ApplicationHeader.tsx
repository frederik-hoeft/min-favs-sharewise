"use client";

import { Anchor, Breadcrumbs, Burger } from "@mantine/core";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { AccountMenu } from "./AccountMenu";
import { appBreadcrumbs } from "./app-routes";
import styles from "./ApplicationHeader.module.scss";

export function ApplicationHeader({ opened, toggleNavigation }: { opened: boolean; toggleNavigation: () => void }) {
  const pathname = usePathname();
  const crumbs = appBreadcrumbs(pathname);

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
      <nav className={styles.breadcrumbs} aria-label="Breadcrumb">
        <Breadcrumbs separator="/" separatorMargin="xs" classNames={{ root: styles.crumbsRow }}>
          {crumbs.map(({ href, label }, index) => index === crumbs.length - 1 ? (
            <span key={href} aria-current="page" className={styles.currentCrumb}>{label}</span>
          ) : (
            <Anchor component={Link} key={href} href={href} className={styles.crumbLink}>{label}</Anchor>
          ))}
        </Breadcrumbs>
      </nav>
      <div className={styles.account}>
        <AccountMenu />
      </div>
    </div>
  );
}

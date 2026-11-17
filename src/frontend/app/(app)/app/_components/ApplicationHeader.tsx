"use client";

import { Anchor, Breadcrumbs, UnstyledButton } from "@mantine/core";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Brand } from "@/components/brand/Brand";
import { AccountMenu } from "./AccountMenu";
import { appBreadcrumbs } from "./app-routes";
import styles from "./ApplicationHeader.module.scss";

export function ApplicationHeader({ opened, toggleNavigation }: { opened: boolean; toggleNavigation: () => void }) {
  const pathname = usePathname();
  const crumbs = appBreadcrumbs(pathname);

  function scrollToTop() {
    window.scrollTo({ top: 0, behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth" });
  }

  return (
    <>
      {/* The button sits behind the interactive header controls. It covers the otherwise empty header surface. */}
      <UnstyledButton className={styles.scrollSurface} aria-label="Scroll to top" onClick={scrollToTop} />
      <div className={styles.content}>
        <Anchor component={Link} href="/app" className={styles.workspaceBrand} aria-label="ShareWise workspace">
          <span className={styles.fullBrand}><Brand /></span>
          <span className={styles.railBrand}><Brand symbolOnly /></span>
        </Anchor>
        <UnstyledButton
          className={styles.mobileBrand}
          onClick={toggleNavigation}
          aria-label={opened ? "Close navigation" : "Open navigation"}
          aria-expanded={opened}
          aria-controls="mobile-app-navigation"
          title={opened ? "Close navigation" : "Open navigation"}
        >
          <Brand symbolOnly />
        </UnstyledButton>
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
    </>
  );
}

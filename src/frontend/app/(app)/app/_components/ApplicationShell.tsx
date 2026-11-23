"use client";

import { AppShell, AppShellHeader, AppShellMain, AppShellNavbar, Container, Drawer, Paper, Text, VisuallyHidden, useMantineTheme } from "@mantine/core";
import { useDisclosure, useMediaQuery } from "@mantine/hooks";
import { useEffect, useState, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import { Brand } from "@/components/brand/Brand";
import { SkipLink } from "@/components/navigation/SkipLink";
import { ApplicationHeader } from "./ApplicationHeader";
import { AppNavigation } from "./AppNavigation";
import styles from "./ApplicationShell.module.scss";

/** The application owns its shell geometry; public routes keep their separate floating layout. */
export function ApplicationShell({ children }: Readonly<{ children: ReactNode }>) {
  const [mobileOpened, { toggle, close }] = useDisclosure(false);
  const [expandedForPath, setExpandedForPath] = useState<string | null>(null);
  const [suppressRailHover, setSuppressRailHover] = useState(false);
  const pathname = usePathname();
  const railExpanded = expandedForPath === pathname;
  const { breakpoints } = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${breakpoints.sm})`);

  useEffect(() => {
    close();
  }, [pathname, close]);

  useEffect(() => {
    if (desktop) close();
  }, [desktop, close]);

  function dismissRail() {
    setExpandedForPath(null);
    // A pointer can remain over the rail after navigation. Do not immediately
    // re-expand until it leaves and enters again (or keyboard focus returns).
    setSuppressRailHover(true);
  }

  return (
    <>
      <SkipLink href="#app-main" />
      <AppShell
        layout="alt"
        padding={0}
        withBorder={false}
        header={{ height: "var(--sw-app-header-height)" }}
        navbar={{ width: { sm: "var(--sw-app-navbar-rail-width)", lg: "var(--sw-app-navbar-width)" }, breakpoint: "sm", collapsed: { mobile: true } }}
      >
        <AppShellHeader className={styles.header} withBorder={false}>
          <ApplicationHeader opened={mobileOpened} toggleNavigation={toggle} />
        </AppShellHeader>
        <AppShellNavbar className={styles.navbar} withBorder={false} aria-label="Workspace navigation">
          <Paper
            radius={0}
            withBorder={false}
            className={styles.navSurface}
            data-expanded={railExpanded}
            onPointerEnter={(event) => {
              if (event.pointerType === "mouse" && !suppressRailHover) setExpandedForPath(pathname);
            }}
            onPointerLeave={() => {
              setExpandedForPath(null);
              setSuppressRailHover(false);
            }}
            onFocusCapture={() => {
              setSuppressRailHover(false);
              setExpandedForPath(pathname);
            }}
            onBlur={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget)) setExpandedForPath(null);
            }}
          >
            <div className={styles.navBrand}>
              <Link href="/app" className={styles.brandLink} aria-label="ShareWise workspace" onClick={dismissRail}>
                <span className={styles.fullBrand}><Brand /></span>
                <span className={styles.railBrand}><Brand symbolOnly /></span>
              </Link>
            </div>
            <div className={styles.navLinks}>
              <Text className={styles.navLabel} size="xs" fw={700} tt="uppercase">Workspace</Text>
              <AppNavigation rail onNavigate={dismissRail} />
            </div>
          </Paper>
        </AppShellNavbar>
        <AppShellMain id="app-main" tabIndex={-1}>
          <Container size="content" className={styles.mainContent}>
            {children}
          </Container>
        </AppShellMain>
      </AppShell>
      <Drawer
        opened={mobileOpened}
        onClose={close}
        position="left"
        size="min(19rem, 90vw)"
        id="mobile-app-navigation"
        title={
          <>
            <span aria-hidden="true"><Brand /></span>
            <VisuallyHidden>Workspace navigation</VisuallyHidden>
          </>
        }
        closeButtonProps={{ "aria-label": "Close workspace navigation" }}
        overlayProps={{ backgroundOpacity: 0.55, blur: 2 }}
        classNames={{
          content: styles.mobileDrawerContent,
          header: styles.mobileDrawerHeader,
          body: styles.mobileDrawerBody,
        }}
      >
        <nav aria-label="Mobile workspace navigation">
          <AppNavigation onNavigate={close} />
        </nav>
      </Drawer>
    </>
  );
}

"use client";

import { ActionIcon, AppShell, AppShellAside, AppShellHeader, AppShellMain, AppShellNavbar, Container, Drawer, Paper, Text, VisuallyHidden, useMantineTheme } from "@mantine/core";
import { InfoIcon } from "@phosphor-icons/react";
import type { AppBreadcrumb } from "./app-routes";
import { useDisclosure, useMediaQuery } from "@mantine/hooks";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import { Brand } from "@/components/brand/Brand";
import { SkipLink } from "@/components/navigation/SkipLink";
import { ApplicationHeader } from "./ApplicationHeader";
import { AppNavigation } from "./AppNavigation";
import { ShellAsideControlsContext, type ShellAsideControls } from "./shell-aside-context";
import styles from "./ApplicationShell.module.scss";

export interface ShellAsideConfiguration {
  readonly label: string;
  readonly content: ReactNode;
  readonly width?: string | number;
}

interface ApplicationShellProps {
  readonly children: ReactNode;
  readonly breadcrumbs?: AppBreadcrumb[];
  readonly aside?: ShellAsideConfiguration;
}

/** The application owns its shell geometry; public routes keep their separate floating layout. */
export function ApplicationShell({ children, breadcrumbs, aside }: ApplicationShellProps) {
  const [mobileOpened, { toggle, close }] = useDisclosure(false);
  const [asideOpened, { open: openAside, close: closeAside }] = useDisclosure(false);
  const [expandedForPath, setExpandedForPath] = useState<string | null>(null);
  const [suppressRailHover, setSuppressRailHover] = useState(false);
  const asideToggle = useRef<HTMLButtonElement>(null);
  const main = useRef<HTMLElement>(null);
  const pathname = usePathname();
  const railExpanded = expandedForPath === pathname;
  const { breakpoints } = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${breakpoints.sm})`);

  const wide = useMediaQuery(`(min-width: ${breakpoints.lg})`);

  useEffect(() => {
    close();
    closeAside();
  }, [pathname, close, closeAside]);

  useEffect(() => { if (wide) closeAside(); }, [wide, closeAside]);

  useEffect(() => {
    if (desktop) close();
  }, [desktop, close]);

  const restoreAsideFocus = useCallback((origin: HTMLElement | null) => {
    const destination = origin?.isConnected && !origin.hasAttribute("disabled") ? origin : asideToggle.current ?? main.current;
    destination?.focus({ preventScroll: true });
  }, []);
  const asideControls: ShellAsideControls = { dismiss: closeAside, restoreFocus: restoreAsideFocus };

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
        aside={aside ? { width: aside.width ?? "21rem", breakpoint: "lg", collapsed: { mobile: true } } : undefined}
        navbar={{ width: { sm: "var(--sw-app-navbar-rail-width)", lg: "var(--sw-app-navbar-width)" }, breakpoint: "sm", collapsed: { mobile: true } }}
      >
        <AppShellHeader className={styles.header} withBorder={false}>
          <ApplicationHeader opened={mobileOpened} breadcrumbs={breadcrumbs} toggleNavigation={() => { closeAside(); toggle(); }}
            asideControl={aside && !wide ? <ActionIcon ref={asideToggle} variant="subtle" size="lg" aria-label={aside.label} title={aside.label} aria-expanded={asideOpened} aria-controls="mobile-app-aside" onClick={() => { close(); openAside(); }}><InfoIcon size="1.25rem" aria-hidden="true" /></ActionIcon> : undefined} />
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
        <AppShellMain ref={main} id="app-main" tabIndex={-1}>
          <Container size="content" className={styles.mainContent}>
            {children}
          </Container>
        </AppShellMain>
        {aside && wide ? <AppShellAside className={styles.aside} aria-label={aside.label}><ShellAsideControlsContext.Provider value={asideControls}>{aside.content}</ShellAsideControlsContext.Provider></AppShellAside> : null}
      </AppShell>
      {aside && !wide ? <Drawer id="mobile-app-aside" opened={asideOpened} onClose={closeAside} position="right" size="min(23rem, 100vw)" title={aside.label} closeButtonProps={{ "aria-label": `Close ${aside.label.toLowerCase()}` }} overlayProps={{ backgroundOpacity: 0.55, blur: 2 }} classNames={{ content: styles.mobileDrawerContent, header: styles.mobileDrawerHeader }}>
        <ShellAsideControlsContext.Provider value={asideControls}>{aside.content}</ShellAsideControlsContext.Provider>
      </Drawer> : null}
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

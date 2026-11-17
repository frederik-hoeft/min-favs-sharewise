"use client";

import { AppShell, AppShellMain, AppShellNavbar, Container, Drawer, Paper, Text, useMantineTheme } from "@mantine/core";
import { useDisclosure, useMediaQuery } from "@mantine/hooks";
import { useEffect, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import { ShellHeader } from "@/components/navigation/ShellHeader";
import { SkipLink } from "@/components/navigation/SkipLink";
import { ApplicationHeader } from "./ApplicationHeader";
import { AppNavigation } from "./AppNavigation";
import styles from "./ApplicationShell.module.scss";

/** Routes own their content; this client island owns only responsive navigation state. */
export function ApplicationShell({ children }: Readonly<{ children: ReactNode }>) {
  const [mobileOpened, { toggle, close }] = useDisclosure(false);
  const pathname = usePathname();
  const { breakpoints } = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${breakpoints.sm})`);

  useEffect(() => {
    close();
  }, [pathname, close]);

  useEffect(() => {
    if (desktop) close();
  }, [desktop, close]);

  return (
    <>
      <SkipLink href="#app-main" />
      <AppShell
        padding={0}
        withBorder={false}
        header={{ height: { base: "5rem", "38.125rem": "6rem" } }}
        navbar={{ width: "15.5rem", breakpoint: "sm", collapsed: { mobile: true } }}
      >
        <ShellHeader>
          <ApplicationHeader opened={mobileOpened} toggleNavigation={toggle} />
        </ShellHeader>
        <AppShellNavbar className={styles.navbar} withBorder={false} aria-label="Workspace navigation">
          <Paper className={styles.navSurface} withBorder radius="xl" p="md">
            <Text className={styles.navLabel} size="xs" fw={700} tt="uppercase">Workspace</Text>
            <AppNavigation />
          </Paper>
        </AppShellNavbar>
        <AppShellMain id="app-main">
          <Container size="content" className={styles.mainContent}>
            {children}
          </Container>
        </AppShellMain>
      </AppShell>
      <Drawer
        opened={mobileOpened}
        onClose={close}
        position="left"
        size="19rem"
        title="Workspace navigation"
        id="mobile-app-navigation"
        overlayProps={{ backgroundOpacity: 0.5, blur: 2 }}
        zIndex={19}
        classNames={{ inner: styles.mobileDrawerInner, overlay: styles.mobileDrawerOverlay }}
      >
        <nav aria-label="Mobile workspace navigation">
          <AppNavigation onNavigate={close} />
        </nav>
      </Drawer>
    </>
  );
}

import { AppShellHeader, Container } from "@mantine/core";
import type { PropsWithChildren } from "react";
import styles from "./ShellHeader.module.scss";

/** Shared AppShell header surface; route layouts supply their own navigation content. */
export function ShellHeader({ children }: PropsWithChildren) {
  return (
    <AppShellHeader className={styles.header} withBorder={false}>
      <Container size="content" className={styles.content}>
        {children}
      </Container>
    </AppShellHeader>
  );
}

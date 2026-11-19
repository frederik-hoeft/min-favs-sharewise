import { AppShellHeader, Container } from "@mantine/core";
import type { PropsWithChildren } from "react";
import styles from "./ShellHeader.module.scss";

/** Shared floating header treatment; each route layout owns its content and width. */
export function ShellHeader({ children, fluid = false }: PropsWithChildren<{ fluid?: boolean }>) {
  return (
    <AppShellHeader className={`${styles.header} ${fluid ? styles.fluid : ""}`} withBorder={false}>
      <Container fluid={fluid} size={fluid ? undefined : "content"} className={styles.content}>
        {children}
      </Container>
    </AppShellHeader>
  );
}

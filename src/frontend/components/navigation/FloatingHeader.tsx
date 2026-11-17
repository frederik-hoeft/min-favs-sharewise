import { Container } from "@mantine/core";
import type { PropsWithChildren } from "react";
import styles from "./FloatingHeader.module.scss";

/** Shared floating header frame; routes own its navigation and action content. */
export function FloatingHeader({ children }: PropsWithChildren) {
  return (
    <header className={styles.header}>
      <Container size="content" className={styles.content}>
        {children}
      </Container>
    </header>
  );
}

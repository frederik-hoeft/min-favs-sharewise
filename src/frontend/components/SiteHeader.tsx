import { Button } from "@mantine/core";
import { Brand } from "./Brand";
import { ColorSchemeControl } from "./ColorSchemeControl";
import { MobileNavigation } from "./MobileNavigation";
import styles from "./SiteHeader.module.scss";

export function SiteHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <Brand />
        <nav className={styles.desktopNav} aria-label="Primary navigation">
          <a href="#platform">Platform</a>
          <a href="#how-it-works">How it works</a>
          <a href="#principles">Our approach</a>
        </nav>
        <div className={styles.actions}>
          <ColorSchemeControl />
          <Button component="a" href="#platform" variant="light" size="sm" className={styles.headerCta}>
            Explore the product <span aria-hidden="true">↗</span>
          </Button>
          <MobileNavigation />
        </div>
      </div>
    </header>
  );
}

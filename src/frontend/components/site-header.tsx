import { Button } from "@mantine/core";
import { Brand } from "./brand";
import { ThemePicker } from "./theme-picker";
import styles from "./site-header.module.scss";

/** Shared public-facing navigation, independent of product/API routes. */
export function SiteHeader() {
  return (
    <header className={styles.header} id="top">
      <div className={styles.inner}>
        <a className={styles.home} href="#top" aria-label="ShareWise, back to top"><Brand /></a>
        <nav className={styles.nav} aria-label="Primary navigation">
          <a href="#workspace">The workspace</a>
          <a href="#approach">Our approach</a>
        </nav>
        <div className={styles.actions}>
          <ThemePicker />
          <Button component="a" href="#workspace" size="sm" variant="outline" className={styles.explore}>
            Explore <span aria-hidden="true">↗</span>
          </Button>
        </div>
      </div>
    </header>
  );
}

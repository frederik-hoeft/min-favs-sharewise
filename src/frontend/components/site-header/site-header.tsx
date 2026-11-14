import { Button } from "@mantine/core";
import { Brand } from "@/components/brand/brand";
import { MobileNavigation } from "./mobile-navigation";
import { siteNavigation } from "./navigation";
import { ThemePicker } from "./theme-picker";
import styles from "./site-header.module.scss";

export function SiteHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <Brand />
        <nav className={styles.desktopNavigation} aria-label="Primary navigation">
          {siteNavigation.map((link) => <a key={link.href} href={link.href}>{link.label}</a>)}
        </nav>
        <div className={styles.actions}>
          <ThemePicker />
          <Button component="a" href="/#inside" className={styles.headerCta}>Take a look <span aria-hidden="true">↗</span></Button>
          <MobileNavigation />
        </div>
      </div>
    </header>
  );
}

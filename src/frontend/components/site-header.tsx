import { Button } from "@mantine/core";
import { AppearanceMenu } from "./appearance-menu";
import { Brand } from "./brand";
import { MobileNavigation } from "./mobile-navigation";
import { navigation } from "./navigation";
import styles from "./site-header.module.scss";

export function SiteHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <Brand />
        <nav aria-label="Primary navigation" className={styles.desktopNav}>
          {navigation.map(({ href, label }) => <a href={href} key={href}>{label}</a>)}
        </nav>
        <div className={styles.actions}>
          <div className={styles.mobileNav}><MobileNavigation /></div>
          <AppearanceMenu />
          <Button component="a" href="#product" color="pine" className={styles.headerCta}>Take a look <span aria-hidden="true">↗</span></Button>
        </div>
      </div>
    </header>
  );
}

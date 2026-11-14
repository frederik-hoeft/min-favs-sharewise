import { Button, Container } from "@mantine/core";
import { BrandMark } from "@/components/brand-mark";
import { ColorSchemeMenu } from "@/components/color-scheme-menu";
import { MobileNavigation } from "./mobile-navigation";
import { landingLinks } from "../_data/navigation";
import styles from "./landing-header.module.scss";

export function LandingHeader() {
  return (
    <>
      <a className={styles.skipLink} href="#content">Skip to content</a>
      <header className={styles.header}>
        <Container size="xl" className={styles.inner}>
          <a href="#top" className={styles.brandLink} aria-label="ShareWise, back to top">
            <BrandMark />
          </a>
          <nav className={styles.desktopNavigation} aria-label="Primary navigation">
            {landingLinks.map(({ label, href }) => <a key={href} href={href}>{label}</a>)}
          </nav>
          <div className={styles.actions}>
            <ColorSchemeMenu />
            <Button component="a" href="#product" className={styles.headerCta} size="sm">
              Explore ShareWise
              <span aria-hidden="true">↗</span>
            </Button>
            <MobileNavigation />
          </div>
        </Container>
      </header>
    </>
  );
}

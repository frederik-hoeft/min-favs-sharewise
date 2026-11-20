import { Button } from "@mantine/core";
import Link from "next/link";
import { Brand } from "@/components/brand/Brand";
import { AppearanceMenu } from "@/components/theme/AppearanceMenu";
import styles from "./PublicNavigation.module.scss";

/** Public-site links and actions are intentionally independent of the shared shell frame. */
export function PublicNavigation() {
  return (
    <>
      <a href="#top" className={styles.brandLink} aria-label="ShareWise, back to top"><Brand compactOnNarrow /></a>
      <nav className={styles.navigation} aria-label="Main navigation">
        <a href="#product">The product</a>
        <a href="#how-it-works">How it works</a>
        <a href="#about">Our approach</a>
      </nav>
      <div className={styles.actions}>
        <AppearanceMenu />
        <Link href="/login" className={styles.login}>Sign in</Link>
        <Link href="/register" className={styles.ctaLink}>
          <Button component="span" size="sm" className={styles.cta}>Get started</Button>
        </Link>
      </div>
    </>
  );
}

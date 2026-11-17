import { Button } from "@mantine/core";
import { Brand } from "@/components/brand/Brand";
import { ArrowUpRightIcon } from "@phosphor-icons/react/ssr";
import { AppearanceMenu } from "@/components/theme/AppearanceMenu";
import styles from "./PublicNavigation.module.scss";

/** Public-site links and actions are intentionally independent of the shared shell frame. */
export function PublicNavigation() {
  return (
    <>
      <a href="#top" className={styles.brandLink} aria-label="ShareWise, back to top"><Brand /></a>
      <nav className={styles.navigation} aria-label="Main navigation">
        <a href="#product">The product</a>
        <a href="#how-it-works">How it works</a>
        <a href="#about">Our approach</a>
      </nav>
      <div className={styles.actions}>
        <AppearanceMenu />
        <Button component="a" href="#product" size="sm" className={styles.cta}
          rightSection={<ArrowUpRightIcon size="1rem" aria-hidden="true" />}>
          Take a look
        </Button>
      </div>
    </>
  );
}

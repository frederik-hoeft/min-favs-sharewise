import { ThemeControl } from "@/components/theme-control/theme-control";
import { ShareWiseMark } from "@/components/brand/sharewise-brand";
import styles from "./site-header.module.scss";

export function SiteHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <a href="#top" className={styles.brand} aria-label="ShareWise, back to top">
          <ShareWiseMark className={styles.mark} />
          <span>sharewise<span className={styles.brandDot}>.</span></span>
        </a>
        <nav className={styles.nav} aria-label="Main navigation">
          <a href="#product">The product</a>
          <a href="#approach">How it works</a>
          <a href="#history">The record</a>
        </nav>
        <div className={styles.actions}>
          <ThemeControl />
          <a href="#product" className={styles.headerLink}>Explore <span aria-hidden="true">↗</span></a>
        </div>
      </div>
    </header>
  );
}

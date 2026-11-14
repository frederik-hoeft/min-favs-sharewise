import { Brand } from "@/components/Brand";
import { SiteHeader } from "@/components/SiteHeader";
import { LandingHero } from "./_components/LandingHero";
import { HistoryStory, PlatformStory, PrinciplesStory } from "./_components/ProductStory";
import styles from "./page.module.scss";

export default function Home() {
  return (
    <div id="top" className={styles.site}>
      <a className={styles.skipLink} href="#main-content">Skip to content</a>
      <SiteHeader />
      <main id="main-content">
        <LandingHero />
        <PlatformStory />
        <HistoryStory />
        <PrinciplesStory />
      </main>
      <footer className={styles.footer}>
        <div className={styles.footerInner}>
          <div><Brand /><p>Shared finances, in focus.</p></div>
          <nav aria-label="Footer navigation"><a href="#platform">Platform</a><a href="#how-it-works">How it works</a><a href="#principles">Our approach</a></nav>
          <span className={styles.footerMeta}>SHAREWISE / CONCEPT 2026<br />STATIC DESIGN PROTOTYPE</span>
        </div>
        <div className={styles.footerBottom}><span>PRECISION IN EVERY PART.</span><span>MADE FOR THE WAY WE SHARE.</span></div>
      </footer>
    </div>
  );
}

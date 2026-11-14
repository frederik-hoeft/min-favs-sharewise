import { Badge, Button } from "@mantine/core";
import { Brand } from "@/components/brand/brand";
import { ThemeSelector } from "@/components/theme-selector/theme-selector";
import { FeatureStories } from "./_components/feature-stories";
import { WorkspacePreview } from "./_components/workspace-preview";
import { preview, euro } from "./_data/preview";
import styles from "./page.module.scss";

export default function Home() {
  return (
    <>
      <a className={styles.skipLink} href="#main">Skip to content</a>
      <header id="top" className={styles.siteHeader}>
        <div className={`${styles.container} ${styles.headerInner}`}>
          <a href="#top" className={styles.brandLink} aria-label="ShareWise home"><Brand /></a>
          <nav className={styles.desktopNav} aria-label="Main navigation">
            <a href="#inside">The idea</a>
            <a href="#how-it-works">How it works</a>
            <a href="#history">Financial history</a>
          </nav>
          <div className={styles.headerActions}>
            <ThemeSelector />
            <Button component="a" className={styles.headerCta} href="#how-it-works" size="sm" rightSection={<span aria-hidden="true">↗</span>}>Explore features</Button>
            <details className={styles.mobileNav}>
              <summary aria-label="Open navigation menu"><span aria-hidden="true">☰</span></summary>
              <nav aria-label="Mobile navigation">
                <a href="#inside">The idea</a>
                <a href="#how-it-works">How it works</a>
                <a href="#history">Financial history</a>
              </nav>
            </details>
          </div>
        </div>
      </header>

      <main id="main">
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={`${styles.container} ${styles.heroGrid}`}>
            <div className={styles.heroCopy}>
              <span className={styles.eyebrow}><span className={styles.eyebrowLine} /> THE GOOD KIND OF SHARING</span>
              <h1 id="hero-title">More together.<br /><em>Less to untangle.</em></h1>
              <p className={styles.heroDescription}>Weekend escapes, shared homes, everyday plans. Keep the people, the expenses and the whole story in one clear place.</p>
              <div className={styles.heroActions}>
                <Button component="a" href="#inside" size="lg" rightSection={<span aria-hidden="true">↗</span>}>Take a closer look</Button>
                <a href="#how-it-works" className={styles.textLink}>See how it works <span aria-hidden="true">→</span></a>
              </div>
              <div className={styles.heroPostscript}>
                <span className={styles.postscriptBadge} aria-hidden="true">✳</span>
                <span>Built around real group finances.<br />Not another payment app.</span>
              </div>
            </div>
            <div className={styles.heroProduct}>
              <div className={styles.decorativeRing} aria-hidden="true" />
              <WorkspacePreview />
            </div>
          </div>
          <div className={`${styles.container} ${styles.heroBottom}`}>
            <span>KEEP THE MOMENTS.</span><span className={styles.heroBottomLine} /><span>KNOW THE NUMBERS.</span>
          </div>
        </section>

        <section id="inside" className={styles.introSection} aria-labelledby="intro-title">
          <div className={`${styles.container} ${styles.introGrid}`}>
            <div><span className={styles.sectionLabel}>THE BIG PICTURE <span>↘</span></span><h2 id="intro-title">A little clarity goes <em>a long way.</em></h2></div>
            <div className={styles.introCopy}><p>When costs get shared, details tend to get scattered. ShareWise brings them back together: who paid, who shares the cost, what&apos;s been settled and what changed along the way.</p><p>Everyone has the same picture, without turning the group chat into a spreadsheet.</p></div>
          </div>
          <div className={`${styles.container} ${styles.principles}`} aria-label="ShareWise principles">
            <div><span className={styles.principleIcon} aria-hidden="true">✳</span><span>Shared context</span><small>Everyone on the same page</small></div>
            <div><span className={styles.principleIcon} aria-hidden="true">≋</span><span>Clear balances</span><small>No guesswork about who owes</small></div>
            <div><span className={styles.principleIcon} aria-hidden="true">◷</span><span>Lasting history</span><small>A record you can look back on</small></div>
          </div>
        </section>

        <section id="how-it-works" className={styles.howSection} aria-labelledby="how-title">
          <div className={styles.container}>
            <div className={styles.howHeading}>
              <div><span className={styles.sectionLabel}>THE WAY IT WORKS <span>↘</span></span><h2 id="how-title">Good company.<br /><em>Better bookkeeping.</em></h2></div>
              <p>A simple way to look after the practical details of being together, from the first expense to the last recorded settlement.</p>
            </div>
            <FeatureStories />
          </div>
        </section>

        <section id="history" className={styles.historySection} aria-labelledby="history-title">
          <div className={`${styles.container} ${styles.historyGrid}`}>
            <div className={styles.historyCopy}>
              <span className={styles.sectionLabel}>NOT JUST TODAY&apos;S TOTAL <span>↘</span></span>
              <h2 id="history-title">The full story,<br /><em>still there.</em></h2>
              <p>Plans change. So do expenses. ShareWise keeps a financial record of revisions and externally recorded settlements, so the past doesn&apos;t disappear when the numbers move.</p>
              <div className={styles.historyCallout}><span aria-hidden="true">↗</span><span>See what changed, who recorded it and how a group&apos;s balances looked at an earlier point in time.</span></div>
            </div>
            <div className={styles.historyVisual} aria-label="Illustrative financial history">
              <div className={styles.historyTop}><span>FINANCIAL HISTORY / {preview.name.toUpperCase()}</span><Badge color="forest" variant="light" radius="sm">Illustrative</Badge></div>
              <div className={styles.timeline}>
                {preview.events.map((event) => (
                  <div className={styles.timelineRow} key={event.revision}>
                    <span className={styles.timelineGlyph} aria-hidden="true">{event.kind === "settlement" ? "↗" : event.kind === "revision" ? "↻" : "+"}</span>
                    <div><strong>{event.label}</strong><span>{event.detail}</span></div>
                    <span className={styles.timelineDate}>{event.day}<small>rev. {event.revision}</small></span>
                  </div>
                ))}
              </div>
              <div className={styles.snapshot}>
                <div className={styles.snapshotTop}><span>LOOKING BACK</span><span>Group revision {preview.historicalSnapshot.revision}</span></div>
                <div className={styles.snapshotBalances}>
                  <div><span>Maya was owed</span><strong>+{euro(preview.historicalSnapshot.maya)}</strong></div>
                  <span className={styles.snapshotDivider} aria-hidden="true" />
                  <div><span>Noah owed</span><strong>−{euro(Math.abs(preview.historicalSnapshot.noah))}</strong></div>
                </div>
                <p>Historical position before the dinner revision and external settlement.</p>
              </div>
            </div>
          </div>
        </section>

        <section className={styles.closingSection} aria-labelledby="closing-title">
          <div className={`${styles.container} ${styles.closingInner}`}>
            <div className={styles.closingSymbol} aria-hidden="true">✳</div>
            <span className={styles.sectionLabel}>KEEP IT TOGETHER</span>
            <h2 id="closing-title">More enjoying the moment.<br /><em>Less figuring it out later.</em></h2>
            <p>A thoughtful way to share costs, keep your records straight and make space for the good stuff.</p>
            <Button component="a" size="lg" href="#top" rightSection={<span aria-hidden="true">↑</span>}>Back to the beginning</Button>
          </div>
        </section>
      </main>

      <footer className={styles.footer}>
        <div className={`${styles.container} ${styles.footerTop}`}>
          <Brand compact />
          <p>Shared plans. Clear balances.<br />A little more peace of mind.</p>
          <nav aria-label="Footer navigation"><a href="#inside">The idea</a><a href="#how-it-works">How it works</a><a href="#history">History</a></nav>
        </div>
        <div className={`${styles.container} ${styles.footerBottom}`}><span>© ShareWise · Design exploration</span><span>This is a static product preview, not a live finance service.</span></div>
      </footer>
    </>
  );
}

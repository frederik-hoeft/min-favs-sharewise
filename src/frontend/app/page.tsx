import { Badge, Button } from "@mantine/core";
import { Brand } from "@/components/brand";
import { SiteHeader } from "@/components/site-header";
import { CostStory } from "./_components/cost-story";
import { HistoryPreview } from "./_components/history-preview";
import { OverviewPreview } from "./_components/overview-preview";
import styles from "./page.module.scss";

export default function Home() {
  return (
    <>
      <a className={styles.skipLink} href="#main-content">Skip to content</a>
      <SiteHeader />
      <main id="main-content">
        <section className={styles.hero} aria-labelledby="hero-heading">
          <div className={styles.heroInner}>
            <div className={styles.heroCopy}>
              <div className={styles.kicker}><span className={styles.kickerMark}>✳</span> THE BETTER WAY TO SHARE</div>
              <h1 id="hero-heading">Good times.<br /><em>Clear tabs.</em></h1>
              <p>Keep the memories. Leave the mental math behind. ShareWise brings your people, shared costs, and every little change into one thoughtful place.</p>
              <div className={styles.heroActions}>
                <Button component="a" href="#product" color="pine" size="lg" rightSection={<span aria-hidden="true">↗</span>}>
                  Explore ShareWise
                </Button>
                <a className={styles.textLink} href="#approach">See how it works <span aria-hidden="true">↗</span></a>
              </div>
              <div className={styles.heroMeta}><span className={styles.smallRule} /> Built for people who share more than bills.</div>
            </div>
            <div className={styles.heroArtwork} aria-hidden="true">
              <div className={styles.orbit}>
                <span className={styles.orbitLetter}>S</span>
                <span className={`${styles.orbitDisc} ${styles.orbitDiscOne}`}>4</span>
                <span className={`${styles.orbitDisc} ${styles.orbitDiscTwo}`}>€</span>
                <span className={`${styles.orbitDisc} ${styles.orbitDiscThree}`}>✓</span>
                <span className={styles.orbitLabel}>GOOD THINGS<br />ARE SHARED.</span>
              </div>
              <div className={styles.heroArtworkCaption}><span>01 / 03</span><span>THE HUMAN SIDE OF SPLITTING</span></div>
            </div>
          </div>
          <div className={styles.heroBottom}><span>THE GROUP FINANCE COMPANION</span><span className={styles.heroScroll}>SCROLL TO EXPLORE <span aria-hidden="true">↓</span></span></div>
        </section>

        <section id="product" className={styles.productSection} aria-labelledby="product-heading">
          <div className={styles.productInner}>
            <div className={styles.sectionHeadingLight}>
              <div><span className={styles.sectionIndex}>01 / THE BIG PICTURE</span><h2 id="product-heading">Your people.<br /><em>Your whole picture.</em></h2></div>
              <p>From the first shared expense to the last recorded settlement, see exactly where the group stands. Without endless messages or mystery numbers.</p>
            </div>
            <div className={styles.previewWrap}>
              <div className={styles.previewHalo} aria-hidden="true" />
              <OverviewPreview />
            </div>
            <div className={styles.productFoot}><span><span className={styles.dot} /> A single, understandable group view</span><span>Illustrative product concept, not a live account</span></div>
          </div>
        </section>

        <section id="approach" className={styles.approachSection} aria-labelledby="approach-heading">
          <div className={styles.sectionTop}>
            <div><span className={styles.sectionIndex}>02 / IT ALL ADDS UP</span><h2 id="approach-heading">Thoughtful about<br /><em>every little detail.</em></h2></div>
            <div className={styles.topSide}><Badge variant="outline" color="pine" radius="xl">MADE FOR REAL LIFE</Badge><p>Fair shares, clear records, and fewer awkward conversations. The simple stuff deserves to be simple.</p></div>
          </div>
          <CostStory />
          <div className={styles.approachCaption}><span>SHARE THE COST, NOT THE CONFUSION.</span><span>Expenses, allocations &amp; externally recorded settlements.</span></div>
        </section>

        <section id="history" className={styles.historySection} aria-labelledby="history-heading">
          <div className={styles.historyInner}>
            <div className={styles.historyCopy}>
              <span className={styles.sectionIndex}>03 / HONEST HISTORY</span>
              <h2 id="history-heading">Clarity today.<br /><em>Context tomorrow.</em></h2>
              <p>Plans change. So do expenses. Keep a record of what was added, corrected or settled, and revisit the group&apos;s finances as they stood before.</p>
              <div className={styles.historyNote}><span aria-hidden="true">✳</span> History gives context to recorded changes. It isn&apos;t proof of an external payment.</div>
            </div>
            <HistoryPreview />
          </div>
        </section>

        <section className={styles.closingSection} aria-labelledby="closing-heading">
          <span className={styles.sectionIndex}>MORE SHARING. LESS SPREADSHEET.</span>
          <h2 id="closing-heading">Money&apos;s a detail.<br /><em>People are the point.</em></h2>
          <p>Made for flatmates, friends, travel crews and everyone who likes things a little more transparent.</p>
          <Button component="a" href="#product" color="pine" size="lg" rightSection={<span aria-hidden="true">↑</span>}>Back to the product</Button>
          <span className={styles.closingShape} aria-hidden="true">✳</span>
        </section>
      </main>
      <footer className={styles.footer} id="about">
        <div className={styles.footerInner}>
          <Brand />
          <p>A clearer way to keep track, together.</p>
          <div className={styles.footerLinks}><a href="#product">Product</a><a href="#approach">How it works</a><a href="#history">History</a><a href="#main-content">Back to top ↑</a></div>
          <div className={styles.footerBottom}><span>SHAREWISE / DESIGN CONCEPT 03</span><span>STATIC PROTOTYPE · NO PAYMENT PROCESSING</span></div>
        </div>
      </footer>
    </>
  );
}

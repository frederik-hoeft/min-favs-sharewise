import { Badge, Button, Container } from "@mantine/core";
import { BrandMark } from "@/components/brand-mark";
import { ExpensePreview, SettlementPreview } from "./_components/expense-preview";
import { HistoryPreview } from "./_components/history-preview";
import { LandingHeader } from "./_components/landing-header";
import { WorkspacePreview } from "./_components/workspace-preview";
import styles from "./page.module.scss";

export default function Home() {
  return (
    <div className={styles.page} id="top">
      <LandingHeader />
      <main id="content">
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={styles.heroPattern} aria-hidden="true" />
          <Container size="xl" className={styles.heroGrid}>
            <div className={styles.heroCopy}>
              <div className={styles.eyebrow}><span /> SHARED FINANCES, SIMPLIFIED</div>
              <h1 id="hero-title">Better together.<br /><span>Clearer by design.</span></h1>
              <p>
                Shared plans are the good part. Keeping track of who paid what shouldn&apos;t
                be the hard part. Give your group one clear place for expenses,
                balances and everything that changes along the way.
              </p>
              <div className={styles.heroActions}>
                <Button component="a" href="#product" size="lg" className={styles.heroButton}>
                  Explore the product <span className={styles.buttonArrow} aria-hidden="true">↗</span>
                </Button>
                <a className={styles.textLink} href="#how-it-works">
                  See how it works <span aria-hidden="true">→</span>
                </a>
              </div>
              <div className={styles.heroAssurances}>
                <span><b aria-hidden="true">✓</b> One shared overview</span>
                <span><b aria-hidden="true">✓</b> Traceable financial history</span>
              </div>
            </div>
            <div className={styles.heroVisual}>
              <div className={styles.visualHalo} aria-hidden="true" />
              <WorkspacePreview />
              <div className={styles.floatCard} aria-hidden="true">
                <span className={styles.floatGlyph}>↗</span>
                <span><small>Recorded outside the app</small><strong>€60.00 settlement</strong></span>
                <span className={styles.floatCheck}>✓</span>
              </div>
            </div>
          </Container>
        </section>

        <section className={styles.principles} aria-label="ShareWise principles">
          <Container size="xl" className={styles.principlesGrid}>
            <div><span className={styles.principleNumber}>01</span><strong>Everyone in the loop</strong><p>Groups and participants, organized in one place.</p></div>
            <div><span className={styles.principleNumber}>02</span><strong>Every share accounted for</strong><p>Clear allocations and up-to-date group balances.</p></div>
            <div><span className={styles.principleNumber}>03</span><strong>Nothing lost in the edits</strong><p>A financial record that preserves earlier revisions.</p></div>
          </Container>
        </section>

        <section className={styles.productSection} id="product" aria-labelledby="product-title">
          <Container size="xl">
            <div className={styles.sectionHeading}>
              <div>
                <span className={styles.sectionKicker}>BUILT FOR REAL GROUPS</span>
                <h2 id="product-title">The details, beautifully handled.</h2>
              </div>
              <p>From the first shared expense to the last recorded settlement, make sense of the money without losing sight of the people.</p>
            </div>
            <div className={styles.previewGrid} id="how-it-works">
              <ExpensePreview />
              <SettlementPreview />
            </div>
            <div className={styles.featureFootnote}>
              <span className={styles.footnoteMark}>✳</span>
              <span><strong>Flexible by nature.</strong> ShareWise&apos;s model supports equal, fixed-amount and percentage allocations. The example above shows an equal split.</span>
            </div>
          </Container>
        </section>

        <section className={styles.historySection} id="history" aria-labelledby="history-title">
          <Container size="xl" className={styles.historyGrid}>
            <div className={styles.historyCopy}>
              <Badge size="lg" variant="light" color="ocean" radius="xl">THE FULL PICTURE</Badge>
              <h2 id="history-title">Clarity today.<br /><span>Context tomorrow.</span></h2>
              <p>
                A balance tells you where things stand. A history tells you how you got there.
                Keep the record of new expenses, corrections and external settlements together.
              </p>
              <div className={styles.historyBenefits}>
                <div><span className={styles.benefitIcon}>↺</span><span>Corrections preserve earlier revisions.</span></div>
                <div><span className={styles.benefitIcon}>↗</span><span>External settlements are recorded, not processed.</span></div>
                <div><span className={styles.benefitIcon}>◎</span><span>Review balances at historical group revisions.</span></div>
              </div>
            </div>
            <div className={styles.historyVisual}>
              <HistoryPreview />
            </div>
          </Container>
        </section>

        <section className={styles.closing} aria-labelledby="closing-title">
          <Container size="xl">
            <div className={styles.closingPanel}>
              <div className={styles.closingOrnament} aria-hidden="true" />
              <div className={styles.closingContent}>
                <span>LESS FRICTION. MORE TOGETHER.</span>
                <h2 id="closing-title">Keep the good memories.<br />We&apos;ll keep the numbers clear.</h2>
                <p>A thoughtful home for shared costs, group balances and the complete financial story.</p>
                <Button component="a" href="#top" size="lg" color="white" variant="white" c="#1d456f">
                  Back to the overview <span className={styles.buttonArrow} aria-hidden="true">↑</span>
                </Button>
              </div>
            </div>
          </Container>
        </section>
      </main>
      <footer className={styles.footer}>
        <Container size="xl" className={styles.footerInner}>
          <BrandMark />
          <p>ShareWise · A clearer way to share costs.</p>
          <a href="#top">Back to top ↑</a>
        </Container>
        <Container size="xl" className={styles.footerDisclaimer}>
          <p>Design concept only. Illustrative financial data. ShareWise records externally made transfers; it does not move or verify money.</p>
        </Container>
      </footer>
    </div>
  );
}

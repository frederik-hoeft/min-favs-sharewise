import { Button } from "@mantine/core";
import { Brand } from "@/components/brand";
import { SiteHeader } from "@/components/site-header";
import { example } from "./_data/example";
import { DetailPreview, HistoryPreview } from "./_components/detail-preview";
import { LedgerPreview } from "./_components/ledger-preview";
import styles from "./page.module.scss";

/** Static candidate page. Only navigation and the global color-scheme control are interactive. */
export default function Home() {
  return (
    <>
      <a href="#main-content" className={styles.skipLink}>Skip to content</a>
      <SiteHeader />
      <main id="main-content">
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={styles.heroBackdrop} aria-hidden="true"><span /><span /><span /></div>
          <div className={styles.container}>
            <div className={styles.heroCopy}>
              <p className={styles.kicker}><span className={styles.kickerLine} /> A BETTER WAY TO SHARE THE EVERYDAY</p>
              <h1 id="hero-title">Good company.<br /><em>Clear accounts.</em></h1>
              <p className={styles.heroDescription}>
                The shared moments matter. The numbers should be simple. Keep expenses,
                balances and the full history of your group in one thoughtful place.
              </p>
              <div className={styles.heroActions}>
                <Button component="a" href="#workspace" size="lg" rightSection={<span aria-hidden="true">↗</span>}>
                  Explore the workspace
                </Button>
                <a href="#approach" className={styles.textLink}>How ShareWise works <span aria-hidden="true">↗</span></a>
              </div>
              <div className={styles.heroFootnote}><span className={styles.footnoteMark} /> For housemates, trips and everything shared.</div>
            </div>
            <div className={styles.heroPreview}>
              <div className={styles.previewEyebrow}><span>01 / A CLEARER PICTURE</span><span>GROUP OVERVIEW</span></div>
              <LedgerPreview />
              <div className={styles.previewCaption}><span>THE LAKE HOUSE</span><span>AN ILLUSTRATIVE GROUP, NOT LIVE DATA</span></div>
            </div>
          </div>
        </section>

        <section className={styles.principles} id="approach" aria-labelledby="principles-title">
          <div className={styles.container}>
            <div className={styles.sectionIntro}>
              <p className={styles.kicker}>THE WAY WE SEE IT</p>
              <h2 id="principles-title">Less accounting.<br />More understanding.</h2>
              <p>Everything you need to keep a shared budget understandable. Nothing pretending to move money on your behalf.</p>
            </div>
            <div className={styles.principleGrid}>
              <article className={styles.principle}>
                <span className={styles.principleIndex}>01 <span aria-hidden="true">/</span></span>
                <div className={styles.principleSymbol} aria-hidden="true"><span className={styles.overlapOne} /><span className={styles.overlapTwo} /></div>
                <h3>Everyone in the picture.</h3>
                <p>Bring your people into one group, with a shared view of who paid and what each person owes.</p>
              </article>
              <article className={styles.principle}>
                <span className={styles.principleIndex}>02 <span aria-hidden="true">/</span></span>
                <div className={styles.principleSymbol} aria-hidden="true"><span className={styles.linesIcon} /></div>
                <h3>Every amount accounted for.</h3>
                <p>Record expenses, divide costs equally or with custom allocations, and see the resulting balances.</p>
              </article>
              <article className={styles.principle}>
                <span className={styles.principleIndex}>03 <span aria-hidden="true">/</span></span>
                <div className={styles.principleSymbol} aria-hidden="true"><span className={styles.historyIcon}>↶</span></div>
                <h3>Nothing lost to memory.</h3>
                <p>Document settlements made elsewhere and follow changes through an ordered financial history.</p>
              </article>
            </div>
          </div>
        </section>

        <section className={styles.workspaceSection} id="workspace" aria-labelledby="workspace-title">
          <div className={styles.container}>
            <div className={styles.workspaceHeading}>
              <div>
                <p className={styles.kicker}>THOUGHTFUL BY DESIGN</p>
                <h2 id="workspace-title">Details, without<br />the disorder.</h2>
              </div>
              <p>An organized home for every shared cost. From the first receipt to the last adjustment, everything has its place.</p>
            </div>
            <div className={styles.workspaceGrid}>
              <div className={styles.featureText}>
                <span className={styles.featureIndex}>01 / SHARED EXPENSES</span>
                <h3>One expense.<br />A fair picture for all.</h3>
                <p>Track who paid, assign each person their share, and see how the whole group fits together. Equal, fixed and percentage-based splits are part of the model.</p>
                <div className={styles.featureRule} />
                <div className={styles.featureAside}><span className={styles.featureAsideIcon} aria-hidden="true">↗</span><span>Clear contributions.<br />No guesswork afterward.</span></div>
              </div>
              <div className={styles.detailWrap}>
                <div className={styles.artboardLabel}>AN EXPENSE, IN FOCUS <span>02 / 03</span></div>
                <DetailPreview />
              </div>
            </div>
          </div>
        </section>

        <section className={styles.recordSection} aria-labelledby="record-title">
          <div className={styles.container}>
            <div className={styles.recordGrid}>
              <div className={styles.recordVisual}>
                <div className={styles.artboardLabel}>THE FINANCIAL JOURNAL <span>03 / 03</span></div>
                <HistoryPreview />
              </div>
              <div className={styles.recordCopy}>
                <p className={styles.kicker}>A RECORD THAT MAKES SENSE</p>
                <h2 id="record-title">The story behind<br />every number.</h2>
                <p>Plans change. Expenses get corrected. Payments happen. ShareWise preserves financial revisions so your group can understand not just its current position, but how it arrived there.</p>
                <div className={styles.note}><span className={styles.noteGlyph} aria-hidden="true">*</span><span>Review changes and historical group balances without rewriting what came before.</span></div>
              </div>
            </div>
          </div>
        </section>

        <section className={styles.settlementSection} aria-labelledby="settlement-title">
          <div className={styles.container}>
            <div className={styles.settlementGrid}>
              <div className={styles.settlementText}>
                <p className={styles.kicker}>CLEAR RECORDS. REAL-WORLD PAYMENTS.</p>
                <h2 id="settlement-title">Keep the record.<br /><em>Make the payment your way.</em></h2>
                <p>ShareWise does not send money or verify transfers. When group members settle up outside the app, record what happened so everyone can see the updated balances.</p>
              </div>
              <div className={styles.settlementIllustration} role="img" aria-label="Illustrative recorded external settlement from Leo to Mina of sixty euros">
                <div className={styles.settlementTop}><span>EXTERNAL SETTLEMENT</span><span>RECORDED / NOV 11</span></div>
                <div className={styles.settlementPeople}>
                  <div><span className={styles.settlementAvatar}>LE</span><strong>{example.settlements[0].sender}</strong><small>Sent</small></div>
                  <div className={styles.settlementAmount}><strong>{example.settlements[0].amount}</strong><span aria-hidden="true">⟶</span></div>
                  <div><span className={styles.settlementAvatar}>MI</span><strong>{example.settlements[0].receiver}</strong><small>Received</small></div>
                </div>
                <p>Recorded in ShareWise <span aria-hidden="true">·</span> Transfer made outside the app</p>
              </div>
            </div>
          </div>
        </section>

        <section className={styles.closing} aria-labelledby="closing-title">
          <div className={styles.container}>
            <p className={styles.kicker}>A LITTLE MORE CLARITY, TOGETHER.</p>
            <h2 id="closing-title">Shared life is complicated enough.</h2>
            <p>Good records make it easier to enjoy the rest.</p>
            <Button component="a" size="lg" href="#top" variant="outline" rightSection={<span aria-hidden="true">↑</span>}>
              Back to the beginning
            </Button>
          </div>
        </section>
      </main>
      <footer className={styles.footer}>
        <div className={styles.container}>
          <a href="#top" aria-label="ShareWise, back to top"><Brand /></a>
          <p>Considered sharing. Clear accounts.</p>
          <nav aria-label="Footer navigation"><a href="#approach">Approach</a><a href="#workspace">Workspace</a><a href="#top">Back to top ↑</a></nav>
          <small>Design exploration · Static illustrative content</small>
        </div>
      </footer>
    </>
  );
}

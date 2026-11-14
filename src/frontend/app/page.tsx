import { Button } from "@mantine/core";
import { ShareWiseMark } from "@/components/brand/sharewise-brand";
import { SiteHeader } from "@/components/site-header/site-header";
import { ExpenseDetail } from "./_components/expense-detail";
import { GroupPreview } from "./_components/group-preview";
import { HistoryPreview } from "./_components/history-preview";
import styles from "./page.module.scss";

export default function Home() {
  return (
    <>
      <a href="#main-content" className={styles.skipLink}>Skip to content</a>
      <SiteHeader />
      <main id="main-content">
        <section id="top" className={styles.hero} aria-labelledby="hero-heading">
          <div className={styles.pageWrap}>
            <div className={styles.kicker}><span>SHAREWISE / THE SHARED LEDGER</span><span>EST. FOR EVERYDAY LIFE</span></div>
            <div className={styles.heroGrid}>
              <div>
                <span className={styles.sectionIndex}>01 / A CLEARER WAY TO SHARE</span>
                <h1 id="hero-heading">Good accounts.<br /><em>Better together.</em></h1>
              </div>
              <div className={styles.heroSide}>
                <p>Shared life is complicated enough. Your shared expenses shouldn’t be.</p>
                <p className={styles.heroExplanation}>ShareWise brings people, expenses, settlements and balances into one considered record. So everyone can see where things stand.</p>
                <div className={styles.heroActions}>
                  <Button component="a" href="#product" size="lg" rightSection={<span aria-hidden="true">↗</span>}>Explore the product</Button>
                  <a href="#approach" className={styles.textLink}>See how it works <span aria-hidden="true">↘</span></a>
                </div>
              </div>
            </div>
            <div className={styles.heroBottom}><span>LESS RECONCILING. MORE LIVING.</span><span>SCROLL TO EXPLORE <span aria-hidden="true">↓</span></span></div>
          </div>
        </section>

        <section id="product" className={styles.product} aria-labelledby="product-heading">
          <div className={styles.pageWrap}>
            <div className={styles.sectionIntro}>
              <div><span className={styles.sectionIndex}>02 / THE OVERVIEW</span><h2 id="product-heading">Everyone on<br />the same page<span className={styles.period}>.</span></h2></div>
              <p>One place for your group, its participants and every recorded financial position. Details when you need them; clarity at a glance.</p>
            </div>
            <GroupPreview />
          </div>
        </section>

        <section id="approach" className={styles.approach} aria-labelledby="approach-heading">
          <div className={styles.pageWrap}>
            <div className={styles.sectionIntro}>
              <div><span className={styles.sectionIndex}>03 / THE DETAILS</span><h2 id="approach-heading">Fairness is in<br />the details<span className={styles.period}>.</span></h2></div>
              <p>From who paid to who shares the cost, the underlying records stay legible. No mystery arithmetic. No assumptions about how you split.</p>
            </div>
            <div className={styles.recordGrid}>
              <div className={styles.expenseColumn}>
                <div className={styles.featureLabel}><span>01</span><span>SHARED EXPENSES & ALLOCATIONS</span></div>
                <ExpenseDetail />
              </div>
              <div className={styles.settlementColumn}>
                <div className={styles.featureLabel}><span>02</span><span>EXTERNAL SETTLEMENTS</span></div>
                <div className={styles.settlementContent}>
                  <span className={styles.smallTopline}>RECORDED, NOT PROCESSED</span>
                  <h3>Money moves outside.<br /><em>The record stays.</em></h3>
                  <p>A participant records money sent outside ShareWise, without tying it to a particular expense. The app neither moves nor verifies the transfer.</p>
                  <div className={styles.transfer}>
                    <div className={styles.transferPeople}><div><span>FROM</span><strong>Mina</strong></div><span className={styles.transferArrow} aria-hidden="true">→</span><div><span>TO</span><strong>Clara</strong></div></div>
                    <div className={styles.transferAmount}><span>EXTERNAL SETTLEMENT RECORDED</span><strong>€100.00</strong></div>
                  </div>
                  <p className={styles.transferNote}>Example only. Settlement records do not initiate or verify payments.</p>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section id="history" className={styles.history} aria-labelledby="history-heading">
          <div className={styles.pageWrap}>
            <span className={styles.sectionIndex}>04 / THE LONG VIEW</span>
            <div className={styles.historyGrid}>
              <div className={styles.historyCopy}>
                <h2 id="history-heading">Nothing lost.<br /><em>Every change<br />accounted for.</em></h2>
                <p>Expenses get corrected. People settle up. Circumstances change. ShareWise keeps financial revisions and historical positions traceable, so the story behind the balance is never rewritten away.</p>
                <div className={styles.historyAside}><span className={styles.historyFigure}>005</span><span>ILLUSTRATIVE GROUP<br />FINANCIAL REVISION</span></div>
              </div>
              <HistoryPreview />
            </div>
          </div>
        </section>

        <section className={styles.outro} aria-labelledby="outro-heading">
          <div className={styles.pageWrap}>
            <span className={styles.sectionIndex}>A GOOD RECORD MAKES ROOM FOR LIFE</span>
            <div className={styles.outroGrid}>
              <h2 id="outro-heading">Better shared.<br /><em>Better understood.</em></h2>
              <div><p>Less uncertainty in the numbers. More confidence in the group. A little more clarity, all around.</p><Button component="a" href="#product" variant="outline" color="wine" size="lg" rightSection={<span aria-hidden="true">↑</span>}>Back to the overview</Button></div>
            </div>
          </div>
        </section>
      </main>
      <footer className={styles.footer}>
        <div className={styles.pageWrap}>
          <div className={styles.footerRow}>
            <a href="#top" className={styles.footerBrand}><ShareWiseMark /> sharewise<span>.</span></a>
            <nav aria-label="Footer navigation"><a href="#product">Overview</a><a href="#approach">Approach</a><a href="#history">History</a></nav>
          </div>
          <div className={styles.footerBottom}><span>THE SHARED LEDGER / SHAREWISE</span><span>Product views depict illustrative data. No payments are handled here.</span><span>BACK TO TOP <a href="#top" aria-label="Back to top">↑</a></span></div>
        </div>
      </footer>
    </>
  );
}

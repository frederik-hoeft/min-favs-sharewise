import { Badge, Button, Container } from "@mantine/core";
import { Brand } from "@/components/brand/brand";
import { BalanceList } from "@/components/finance/balance-list";
import { currentBalances, participants } from "./_data/illustrative-ledger";
import { AppearanceMenu } from "@/components/navigation/appearance-menu";
import { ExpensePreview } from "./_components/expense-preview";
import { HistoryPreview } from "./_components/history-preview";
import { OverviewPreview } from "./_components/overview-preview";
import styles from "./page.module.scss";

const promises = [
  { number: "01", title: "Your people, your group", copy: "Make a space for everyone sharing the experience, not just the bill." },
  { number: "02", title: "Every share makes sense", copy: "Keep track of who paid, the total cost, and how it was allocated." },
  { number: "03", title: "Paybacks, properly noted", copy: "Record money exchanged elsewhere, without pretending to move it here." },
] as const;

export default function Home() {
  return (
    <div id="top" className={styles.site}>
      <a href="#main-content" className={styles.skipLink}>Skip to content</a>
      <header className={styles.header}>
        <Container className={styles.headerInner} size="xl">
          <a className={styles.brandLink} href="#top" aria-label="ShareWise, back to top"><Brand /></a>
          <nav className={styles.nav} aria-label="Main navigation">
            <a href="#how-it-works">How it works</a>
            <a href="#product">Inside ShareWise</a>
            <a href="#why-sharewise">Our thinking</a>
          </nav>
          <div className={styles.headerActions}>
            <AppearanceMenu />
            <Button component="a" className={styles.headerCta} href="#product" size="sm" color="pine">Explore <span aria-hidden="true">↗</span></Button>
          </div>
        </Container>
      </header>

      <main id="main-content">
        <section className={styles.hero} aria-labelledby="hero-title">
          <Container className={styles.heroInner} size="xl">
            <div className={styles.heroCopy}>
              <div className={styles.eyebrow}><span className={styles.sparkle} aria-hidden="true">✳</span> THE GOOD KIND OF ACCOUNTABILITY</div>
              <h1 id="hero-title">More living.<br /><em>Less</em> keeping<br />score<span className={styles.dot}>.</span></h1>
              <p className={styles.heroDescription}>Shared moments shouldn’t come with messy math. Bring your people, expenses, balances, and the whole story together in one beautifully clear place.</p>
              <div className={styles.heroButtons}>
                <Button component="a" href="#product" color="pine" size="lg" rightSection={<span aria-hidden="true">↗</span>}>Take a look inside</Button>
                <Button component="a" href="#how-it-works" color="pine" variant="outline" size="lg">See how it works</Button>
              </div>
              <div className={styles.heroFootnote}><span className={styles.avatars} aria-hidden="true"><i>M</i><i>N</i><i>L</i></span><span>For flatmates, travel crews, and everyone in between.</span></div>
            </div>
            <div className={styles.heroArt}>
              <div className={styles.heroCircle} aria-hidden="true" />
              <div className={styles.heroArtifact}><OverviewPreview /></div>
              <div className={styles.heroSticker} aria-hidden="true"><span className={styles.stickerMark}>✳</span> all together<br /><strong>now.</strong></div>
            </div>
          </Container>
        </section>

        <section className={styles.ribbon} aria-label="ShareWise at a glance">
          <Container size="xl" className={styles.ribbonInner}>
            <span><span className={styles.ribbonIcon} aria-hidden="true">✳</span> KEEP THE GROUP IN THE LOOP</span>
            <span><span className={styles.ribbonIcon} aria-hidden="true">◉</span> SEE WHERE EVERY EURO WENT</span>
            <span><span className={styles.ribbonIcon} aria-hidden="true">↗</span> KNOW WHAT CHANGED AND WHEN</span>
          </Container>
        </section>

        <section id="how-it-works" className={styles.story} aria-labelledby="story-title">
          <Container size="xl" className={styles.storyGrid}>
            <div className={styles.storyCopy}>
              <p className={styles.sectionKicker}>01 <span /> THE EVERYDAY STUFF</p>
              <h2 id="story-title">A little less<br />“wait, <em>who paid?</em>”</h2>
              <p className={styles.storyIntro}>From Sunday dinners to shared apartments, the small costs add up. Your group needs a record that’s as easy to follow as the plan itself.</p>
              <ol className={styles.promises}>
                {promises.map((item) => (
                  <li key={item.number}>
                    <span className={styles.promiseNumber}>{item.number}</span>
                    <div><h3>{item.title}</h3><p>{item.copy}</p></div>
                  </li>
                ))}
              </ol>
            </div>
            <ExpensePreview />
          </Container>
        </section>

        <section id="product" className={styles.deepSection} aria-labelledby="product-title">
          <Container size="xl">
            <div className={styles.deepHeader}>
              <div><p className={styles.sectionKicker}>02 <span /> THE BIGGER PICTURE</p><h2 id="product-title">Clarity isn’t just<br />a <em>number.</em></h2></div>
              <p>Knowing the balance is good. Knowing how it got there is better. ShareWise keeps the present and the past in the same conversation.</p>
            </div>
            <div className={styles.deepGrid}>
              <div className={styles.balanceStory}>
                <div className={styles.balanceBadge}><span aria-hidden="true">↗</span> FINANCIAL OVERVIEW</div>
                <h3>See where<br />everyone stands.</h3>
                <p>Three people, three shared expenses, and a recorded external settlement. No vague IOUs. Just each person’s net position.</p>
                <div className={styles.balanceCard}>
                  <div className={styles.balanceCardHead}><div className={styles.balanceCardGroup}><span className={styles.balanceCardSubtitle}>Sunday Table</span><strong>Current group balances</strong></div><Badge color="pine" variant="light">REV 05</Badge></div>
                  <BalanceList balances={currentBalances} participants={participants} label="Current net balances for Sunday Table" />
                  <p>All group balances sum to zero.</p>
                </div>
                <div className={styles.settlementNote}><span className={styles.settlementArrow} aria-hidden="true">↗</span><span><strong>A settlement is a record, not a transfer.</strong><small>Payments happen outside ShareWise. They aren’t processed or verified here.</small></span></div>
              </div>
              <div className={styles.historyStory}>
                <HistoryPreview />
              </div>
            </div>
          </Container>
        </section>

        <section id="why-sharewise" className={styles.closing} aria-labelledby="closing-title">
          <Container size="xl" className={styles.closingGrid}>
            <div className={styles.closingGraphic} aria-hidden="true"><div className={styles.orbitOne} /><div className={styles.orbitTwo} /><span>better<br /><i>together</i><b>✳</b></span></div>
            <div className={styles.closingCopy}>
              <p className={styles.sectionKicker}>03 <span /> WHAT MATTERS</p>
              <h2 id="closing-title">Money is a detail.<br /><em>People are the point.</em></h2>
              <p>Keep the memories in the group chat. Keep the expenses, corrections, and settle-ups somewhere everyone can understand.</p>
              <Badge color="pine" variant="outline" size="lg">Built for shared life</Badge>
              <div><Button component="a" href="#top" color="pine" rightSection={<span aria-hidden="true">↑</span>}>Back to the top</Button></div>
            </div>
          </Container>
        </section>
      </main>

      <footer className={styles.footer}>
        <Container size="xl" className={styles.footerInner}>
          <a href="#top" aria-label="ShareWise, back to top"><Brand /></a>
          <p>Less counting. More connecting.<br /><span>Concept preview · Not a live financial application</span></p>
          <a href="#main-content">Back to content ↑</a>
        </Container>
      </footer>
    </div>
  );
}

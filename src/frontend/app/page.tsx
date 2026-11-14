import { Button, Container } from "@mantine/core";
import { Brand } from "@/components/brand/Brand";
import { ThemeSelector } from "@/components/theme-selector/ThemeSelector";
import { ExpenseDetailPreview } from "./_components/ExpenseDetailPreview";
import { HistoryPreview } from "./_components/HistoryPreview";
import { OverviewPreview } from "./_components/OverviewPreview";
import styles from "./page.module.scss";

const navigation = [
  { href: "#benefits", label: "Why ShareWise" },
  { href: "#product", label: "The workspace" },
  { href: "#approach", label: "How it works" },
] as const;

export default function Home() {
  return (
    <>
      <a className={styles.skipLink} href="#top">Skip to content</a>
      <header className={styles.siteHeader}>
        <Container size="var(--sw-content-width)" className={styles.headerInner}>
          <a href="#top" className={styles.brandLink} aria-label="ShareWise, back to top">
            <Brand />
          </a>
          <nav className={styles.navigation} aria-label="Primary navigation">
            {navigation.map((item) => (
              <a key={item.href} href={item.href}>{item.label}</a>
            ))}
          </nav>
          <ThemeSelector />
        </Container>
      </header>

      <main id="top" tabIndex={-1}>
        <section className={styles.hero} aria-labelledby="hero-title">
          <Container size="var(--sw-content-width)" className={styles.heroGrid}>
            <div className={styles.heroCopy}>
              <p className={styles.eyebrow}><span className={styles.eyebrowMark} /> A clearer way to share expenses</p>
              <h1 id="hero-title">Shared costs.<br /><span>Clear understanding.</span></h1>
              <p className={styles.heroDescription}>
                The people, the expenses, and the bigger picture, all in one place.
                ShareWise gives every group a shared understanding of where things stand.
              </p>
              <div className={styles.heroActions}>
                <Button component="a" href="#product" size="lg">Explore the workspace <span aria-hidden="true">↗</span></Button>
                <Button component="a" href="#approach" size="lg" variant="default">How it works</Button>
              </div>
              <p className={styles.heroAside}>A record of shared finances, not a payment service.</p>
            </div>
            <div className={styles.heroVisual}>
              <OverviewPreview />
            </div>
          </Container>
        </section>

        <section className={styles.benefits} id="benefits" aria-labelledby="benefits-title">
          <Container size="var(--sw-content-width)">
            <div className={styles.benefitsHeading}>
              <span className={styles.sectionEyebrow}>WHY SHAREWISE</span>
              <h2 id="benefits-title">A little more order. A lot less uncertainty.</h2>
            </div>
            <div className={styles.benefitGrid}>
              <div className={styles.benefit}>
                <span className={styles.benefitNumber}>01 / PEOPLE</span>
                <h3>One group, one shared view.</h3>
                <p>Keep participants and shared expenses together, without losing track of who is involved.</p>
              </div>
              <div className={styles.benefit}>
                <span className={styles.benefitNumber}>02 / BALANCES</span>
                <h3>Know where everyone stands.</h3>
                <p>See who is owed money and who owes money, based on recorded expenses and settlements.</p>
              </div>
              <div className={styles.benefit}>
                <span className={styles.benefitNumber}>03 / HISTORY</span>
                <h3>Keep the context.</h3>
                <p>Corrections are part of the story. Review revisions and earlier financial positions.</p>
              </div>
            </div>
          </Container>
        </section>

        <section className={styles.product} id="product" aria-labelledby="product-title">
          <Container size="var(--sw-content-width)">
            <div className={styles.productIntro}>
              <div>
                <span className={styles.sectionEyebrow}>A LOOK INSIDE</span>
                <h2 id="product-title">Thoughtful details.<br />A complete picture.</h2>
              </div>
              <p>
                From the original cost split to the latest correction, the important details
                stay connected. The interface makes the record easy to understand, not harder to maintain.
              </p>
            </div>
            <div className={styles.previewGrid}>
              <div className={styles.previewColumn}>
                <ExpenseDetailPreview />
                <div className={styles.previewCaption}>
                  <span className={styles.captionLine} aria-hidden="true" />
                  <div><h3>Every share accounted for.</h3><p>Equal, fixed or percentage allocations belong to the expense they describe.</p></div>
                </div>
              </div>
              <div className={styles.previewColumn}>
                <HistoryPreview />
                <div className={styles.previewCaption}>
                  <span className={styles.captionLine} aria-hidden="true" />
                  <div><h3>Changes with a paper trail.</h3><p>External settlements and revisions stay visible, including the group&apos;s earlier balances.</p></div>
                </div>
              </div>
            </div>
            <p className={styles.sampleDisclaimer}>The product views above use illustrative data and are not interactive.</p>
          </Container>
        </section>

        <section className={styles.approach} id="approach" aria-labelledby="approach-title">
          <Container size="var(--sw-content-width)" className={styles.approachInner}>
            <div className={styles.approachIntro}>
              <span className={styles.sectionEyebrow}>THE SIMPLE IDEA</span>
              <h2 id="approach-title">Less back-and-forth.<br />More on the same page.</h2>
            </div>
            <div className={styles.approachList}>
              <div><span>01</span><p><strong>Bring people together.</strong> Create a shared place for group expenses and participants.</p></div>
              <div><span>02</span><p><strong>Record what happened.</strong> Capture expenses, their allocations and money exchanged elsewhere.</p></div>
              <div><span>03</span><p><strong>Understand the result.</strong> Review current balances, corrections and past financial states.</p></div>
            </div>
          </Container>
        </section>

        <section className={styles.closing} aria-label="ShareWise summary">
          <Container size="var(--sw-content-width)" className={styles.closingInner}>
            <div>
              <span className={styles.sectionEyebrow}>SHAREWISE</span>
              <h2>A clearer view of what we share.</h2>
              <p>More clarity for the details, more confidence in the bigger picture.</p>
            </div>
            <Button component="a" href="#top" variant="default" size="lg">Back to top ↑</Button>
          </Container>
        </section>
      </main>

      <footer className={styles.footer}>
        <Container size="var(--sw-content-width)" className={styles.footerInner}>
          <a href="#top" className={styles.brandLink} aria-label="ShareWise, back to top"><Brand /></a>
          <p>Independent design concept · Static preview, not a live financial service.</p>
          <a href="#product">Explore the example</a>
        </Container>
      </footer>
    </>
  );
}

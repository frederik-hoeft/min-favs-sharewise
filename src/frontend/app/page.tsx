import { Button } from "@mantine/core";
import { Brand } from "@/components/brand/Brand";
import { AppearanceMenu } from "@/components/theme/AppearanceMenu";
import { DetailsPreview } from "./_components/DetailsPreview";
import { OverviewPreview } from "./_components/OverviewPreview";
import styles from "./page.module.scss";

const steps = [
  { number: "01", title: "Bring your people together.", text: "Create a group for the trip, home, or shared project. Everyone belongs to the same picture." },
  { number: "02", title: "Add expenses, not confusion.", text: "Record who paid and choose an equal, fixed, or percentage split for each expense." },
  { number: "03", title: "Keep the record straight.", text: "Record settlements made outside the app and revisit past changes whenever you need to." },
];

export default function Home() {
  return (
    <>
      <a className={styles.skip} href="#main">Skip to content</a>
      <header className={styles.siteHeader}>
        <div className={styles.headerInner}>
          <a href="#top" className={styles.brandLink} aria-label="ShareWise, back to top"><Brand /></a>
          <nav className={styles.navigation} aria-label="Main navigation">
            <a href="#product">The product</a>
            <a href="#how-it-works">How it works</a>
            <a href="#about">Our approach</a>
          </nav>
          <div className={styles.headerActions}>
            <AppearanceMenu />
            <Button component="a" href="#product" size="sm" className={styles.headerCta}>Take a look <span aria-hidden="true">↗</span></Button>
          </div>
        </div>
      </header>
      <main id="main">
        <section className={styles.hero} id="top" aria-labelledby="hero-title">
          <div className={styles.heroCopy}>
            <div className={styles.eyebrow}><span className={styles.spark} /> GOOD WITH PEOPLE. GOOD WITH NUMBERS.</div>
            <h1 id="hero-title">Keep the plans.<br /><span>Lose the math.</span></h1>
            <p className={styles.heroLead}>Shared expenses don’t have to be complicated. Keep everyone in sync, see where the money went, and know exactly where things stand.</p>
            <div className={styles.heroActions}>
              <Button component="a" href="#product" size="lg" rightSection={<span aria-hidden="true">↗</span>}>Explore ShareWise</Button>
              <Button component="a" href="#how-it-works" size="lg" variant="subtle" color="gray">See how it works <span aria-hidden="true">→</span></Button>
            </div>
            <div className={styles.heroFootnote}><span aria-hidden="true">✦</span> One place for groups, shared costs, and the story behind them.</div>
          </div>
          <OverviewPreview />
        </section>

        <section className={styles.thought} aria-label="What ShareWise is for">
          <div className={styles.thoughtInner}>
            <span className={styles.thoughtMark}>“</span>
            <p>Less back-and-forth about money.<br /><strong>More time for everything else.</strong></p>
            <span className={styles.thoughtAside}>A clearer way to keep the group together.</span>
          </div>
        </section>

        <section className={styles.productSection} id="product" aria-labelledby="product-title">
          <div className={styles.sectionHeading}>
            <div>
              <span className={styles.sectionKicker}>THE DETAILS, TAKEN CARE OF</span>
              <h2 id="product-title">Made for the way<br />we actually share.</h2>
            </div>
            <p>From splitting the first bill to looking back at a correction, ShareWise keeps the important details easy to find. These are illustrative product views.</p>
          </div>
          <DetailsPreview />
        </section>

        <section className={styles.stepsSection} id="how-it-works" aria-labelledby="steps-title">
          <div className={styles.stepsHeading}>
            <span className={styles.sectionKicker}>SIMPLE BY DESIGN</span>
            <h2 id="steps-title">Good for the group.<br />Easy on everyone.</h2>
          </div>
          <div className={styles.steps}>
            {steps.map((step) => (
              <div className={styles.step} key={step.number}>
                <span className={styles.stepNumber}>{step.number}</span>
                <h3>{step.title}</h3>
                <p>{step.text}</p>
              </div>
            ))}
          </div>
        </section>

        <section className={styles.closing} id="about" aria-labelledby="closing-title">
          <div className={styles.closingMark} aria-hidden="true"><span /><span /></div>
          <div>
            <span className={styles.closingKicker}>MADE FOR SHARING</span>
            <h2 id="closing-title">Better together.<br />Clearer with ShareWise.</h2>
            <p>One shared view of expenses, balances, and the decisions along the way. No payment processing, no mystery math.</p>
          </div>
          <Button component="a" href="#top" color="gray" variant="white" size="md" rightSection={<span aria-hidden="true">↑</span>}>Back to top</Button>
        </section>
      </main>
      <footer className={styles.footer}>
        <Brand />
        <span>Thoughtfully simple group finances.</span>
        <a href="#top">Back to top ↑</a>
      </footer>
    </>
  );
}

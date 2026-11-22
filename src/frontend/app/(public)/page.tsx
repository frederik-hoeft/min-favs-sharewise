import Link from "next/link";
import { Button, Container, Paper, Text, Title } from "@mantine/core";
import { ArrowRightIcon, ArrowUpIcon, ArrowUpRightIcon, SparkleIcon } from "@phosphor-icons/react/ssr";
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
      <section className={styles.hero} id="top" aria-labelledby="hero-title">
        <div className={styles.heroCopy}>
          <div className={styles.eyebrow}><span className={styles.spark} /> GOOD WITH PEOPLE. GOOD WITH NUMBERS.</div>
          <Title order={1} id="hero-title">Keep the plans.<br /><span>Lose the math.</span></Title>
          <Text component="p" className={styles.heroLead}>Shared expenses don’t have to be complicated. Keep everyone in sync, see where the money went, and know exactly where things stand.</Text>
          <div className={styles.heroActions}>
            <Link href="/auth/register" className={styles.heroActionLink}>
              <Button component="span" size="lg" rightSection={<ArrowUpRightIcon size="1rem" aria-hidden="true" />}>Get started</Button>
            </Link>
            <Button component="a" href="#product" size="lg" variant="subtle" color="gray" rightSection={<ArrowRightIcon size="1rem" aria-hidden="true" />}>Explore the product</Button>
          </div>
          <div className={styles.heroFootnote}><SparkleIcon size="1.1875rem" aria-hidden="true" /> One place for groups, shared costs, and the story behind them.</div>
        </div>
        <OverviewPreview />
      </section>

      <section className={styles.thought} aria-label="What ShareWise is for">
        <Container size="content" className={styles.thoughtInner}>
          <span className={styles.thoughtMark}>“</span>
          <p>Less back-and-forth about money.<br /><strong>More time for everything else.</strong></p>
          <span className={styles.thoughtAside}>A clearer way to keep the group together.</span>
        </Container>
      </section>

      <section className={styles.productSection} id="product" aria-labelledby="product-title">
        <div className={styles.sectionHeading}>
          <div>
            <span className={styles.sectionKicker}>THE DETAILS, TAKEN CARE OF</span>
            <Title order={2} id="product-title">Made for the way<br />we actually share.</Title>
          </div>
          <p>From splitting the first bill to looking back at a correction, ShareWise keeps the important details easy to find. These are illustrative product views.</p>
        </div>
        <DetailsPreview />
      </section>

      <section className={styles.stepsSection} id="how-it-works" aria-labelledby="steps-title">
        <Container size="content" px={0} className={styles.stepsHeading}>
          <span className={styles.sectionKicker}>SIMPLE BY DESIGN</span>
          <Title order={2} id="steps-title">Good for the group.<br />Easy on everyone.</Title>
        </Container>
        <Container size="content" px={0} className={styles.steps}>
          {steps.map((step) => (
            <div className={styles.step} key={step.number}>
              <span className={styles.stepNumber}>{step.number}</span>
              <Title order={3}>{step.title}</Title>
              <p>{step.text}</p>
            </div>
          ))}
        </Container>
      </section>

      <Paper component="section" className={styles.closing} id="about" aria-labelledby="closing-title">
        <div className={styles.closingMark} aria-hidden="true"><span /><span /></div>
        <div>
          <span className={styles.closingKicker}>MADE FOR SHARING</span>
          <Title order={2} id="closing-title">Better together.<br />Clearer with ShareWise.</Title>
          <p>One shared view of expenses, balances, and the decisions along the way. No payment processing, no mystery math.</p>
        </div>
        <Button component="a" href="#top" color="dark" variant="white" size="md" rightSection={<ArrowUpIcon size="1rem" aria-hidden="true" />}>Back to top</Button>
      </Paper>
    </>
  );
}

import { Button } from "@mantine/core";
import { Brand } from "@/components/brand/brand";
import { SiteHeader } from "@/components/site-header/site-header";
import { siteNavigation } from "@/components/site-header/navigation";
import { AllocationPreview } from "./_components/allocation-preview";
import { DashboardPreview } from "./_components/dashboard-preview";
import { HistoryPreview } from "./_components/history-preview";
import styles from "./page.module.scss";

export default function Home() {
  return (
    <>
      <a className={styles.skipLink} href="#main-content">Skip to content</a>
      <SiteHeader />
      <main id="main-content">
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={styles.heroInner}>
            <div className={styles.heroCopy}>
              <div className={styles.eyebrow}><span className={styles.liveDot} /> THE JOY OF SHARING, MINUS THE MATH</div>
              <h1 id="hero-title">Good times.<br /><em>Clear tabs.</em></h1>
              <p className={styles.lead}>Make memories together. Keep the money side easy to understand. One thoughtful place for your people, shared expenses, and every change along the way.</p>
              <div className={styles.heroActions}>
                <Button component="a" href="#inside" size="lg" rightSection={<span aria-hidden="true">↗</span>}>Explore ShareWise</Button>
                <a className={styles.textLink} href="#why-sharewise">The idea behind it <span aria-hidden="true">↗</span></a>
              </div>
              <div className={styles.heroDetail}><span className={styles.starburst} aria-hidden="true">✳</span> More time together. Less “who owes who?”</div>
            </div>
            <div className={styles.heroVisual}>
              <div className={styles.heroOrbit} aria-hidden="true" />
              <div className={styles.heroTopTag}><span aria-hidden="true">↗</span> A clearer way to share</div>
              <DashboardPreview />
              <div className={styles.heroFloat} aria-hidden="true"><span>✳</span> it all adds up.</div>
            </div>
          </div>
        </section>

        <section id="why-sharewise" className={styles.manifesto} aria-labelledby="manifesto-heading">
          <div className={styles.sectionInner}>
            <div className={styles.sectionLabel}><span>01 / THE IDEA</span><span className={styles.labelRule} /></div>
            <div className={styles.manifestoGrid}>
              <h2 id="manifesto-heading">Together is better.<br /><em>So is clarity.</em></h2>
              <div className={styles.manifestoCopy}>
                <p>Weekend away? Shared flat? One big group plan? The good stuff gets complicated when the costs are scattered across chats, notes and half-remembered transfers.</p>
                <p><strong>ShareWise gives every group a shared point of reference.</strong> Who joined, what was spent, how it was split, and what happened next.</p>
              </div>
            </div>
            <div className={styles.principles}>
              <div><span className={styles.principleNumber}>01</span><h3>Your people, in one place</h3><p>Keep your group and its participants connected to the costs you share.</p></div>
              <div><span className={styles.principleNumber}>02</span><h3>Every share accounted for</h3><p>See who paid and how each expense was allocated, not just a total.</p></div>
              <div><span className={styles.principleNumber}>03</span><h3>Nothing lost in the edits</h3><p>A lasting financial record means revisions don’t erase what came before.</p></div>
            </div>
          </div>
        </section>

        <section id="inside" className={styles.inside} aria-labelledby="inside-heading">
          <div className={styles.sectionInner}>
            <div className={styles.sectionLabel}><span>02 / INSIDE SHAREWISE</span><span className={styles.labelRule} /></div>
            <div className={styles.insideIntro}>
              <h2 id="inside-heading">The details,<br /><em>beautifully sorted.</em></h2>
              <p>From the first shared expense to the final settled-up moment, everyone sees the same story. Without a spreadsheet in sight.</p>
            </div>
            <div className={styles.allocationLayout}>
              <div className={styles.allocationVisual}><span className={styles.cornerCircle} aria-hidden="true" /><AllocationPreview /></div>
              <div className={styles.allocationText}>
                <span className={styles.overline}>A LITTLE MORE TRANSPARENT</span>
                <h3>Know exactly<br />where every<br /><em>euro goes.</em></h3>
                <p>Equal shares for a cabin rental. Individual amounts for something more specific. Every expense keeps the payer and each participant’s allocated share together.</p>
                <div className={styles.detailLine}><span className={styles.detailIcon} aria-hidden="true">✓</span><span>Equal, fixed and percentage allocations</span></div>
                <div className={styles.detailLine}><span className={styles.detailIcon} aria-hidden="true">✓</span><span>Clear totals that always account for the full cost</span></div>
              </div>
            </div>
            <div className={styles.twoCards}>
              <article className={styles.settlementCard}>
                <div className={styles.cardTopline}><span>OUTSIDE THE APP</span><span aria-hidden="true">↗</span></div>
                <h3>A transfer made?<br /><em>Make a note.</em></h3>
                <p>ShareWise records settlements between group members. The actual money moves outside the app, never through it.</p>
                <div className={styles.transferVisual} aria-label="Illustrative external transfer of forty euros from Dani to Ben">
                  <span className={styles.transferPerson}><span className={styles.transferAvatar}>DA</span>Dani</span>
                  <span className={styles.transferMiddle}><strong>€40</strong><small>recorded →</small></span>
                  <span className={styles.transferPerson}><span className={`${styles.transferAvatar} ${styles.transferBen}`}>BE</span>Ben</span>
                </div>
                <span className={styles.cardFineprint}>A record of a transfer, not a payment confirmation</span>
              </article>
              <article className={styles.balancesCard}>
                <div className={styles.cardTopline}><span>ALL ON THE SAME PAGE</span><span aria-hidden="true">✳</span></div>
                <h3>The picture<br /><em>at a glance.</em></h3>
                <p>See who is owed money and who owes money in the group, after expenses and any recorded settlements.</p>
                <div className={styles.balancePills}>
                  <span><small>Ava</small><strong>+€20</strong></span>
                  <span><small>Ben</small><strong>+€100</strong></span>
                  <span><small>Cleo</small><strong>−€60</strong></span>
                  <span><small>Dani</small><strong>−€60</strong></span>
                </div>
                <span className={styles.cardFineprint}>Illustrative balances in EUR · net total €0</span>
              </article>
            </div>
          </div>
        </section>

        <section id="the-record" className={styles.record} aria-labelledby="record-heading">
          <div className={styles.sectionInner}>
            <div className={`${styles.sectionLabel} ${styles.recordLabel}`}><span>03 / THE BIGGER PICTURE</span><span className={styles.labelRule} /></div>
            <div className={styles.recordGrid}>
              <div className={styles.recordText}>
                <span className={styles.recordKicker}>EVERY CHAPTER COUNTS</span>
                <h2 id="record-heading">The numbers change.<br /><em>The story stays.</em></h2>
                <p>Plans evolve. People correct expenses. Transfers happen later. ShareWise keeps a group-level history of financial changes and lets you understand a balance at an earlier point in that story.</p>
                <div className={styles.recordPullquote}><span aria-hidden="true">“</span> Clarity isn’t just knowing where you stand. It’s knowing how you got there.</div>
              </div>
              <HistoryPreview />
            </div>
          </div>
        </section>

        <section className={styles.closing} aria-labelledby="closing-heading">
          <div className={styles.sectionInner}>
            <div className={styles.closingMark} aria-hidden="true">✳</div>
            <p className={styles.closingEyebrow}>SHARE EXPERIENCES. NOT CONFUSION.</p>
            <h2 id="closing-heading">Keep the plans.<br /><em>Lose the guesswork.</em></h2>
            <p className={styles.closingNote}>A little more clarity for all the things you do together.</p>
            <Button component="a" href="#main-content" size="lg" variant="filled" rightSection={<span aria-hidden="true">↑</span>}>Back to the beginning</Button>
          </div>
        </section>
      </main>
      <footer className={styles.footer}>
        <div className={styles.footerInner}>
          <Brand />
          <nav aria-label="Footer navigation">{siteNavigation.map((link) => <a key={link.href} href={link.href}>{link.label}</a>)}</nav>
          <span>Design exploration · Static concept, not a live service</span>
        </div>
      </footer>
    </>
  );
}

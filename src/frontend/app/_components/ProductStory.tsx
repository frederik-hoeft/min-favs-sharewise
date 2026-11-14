import { Badge, Button } from "@mantine/core";
import { previewGroup } from "./financeFixtures";
import styles from "./ProductStory.module.scss";

function SectionEyebrow({ number, children }: { number: string; children: React.ReactNode }) {
  return <p className={styles.eyebrow}><span>{number}</span> {children}</p>;
}

function GroupArtwork() {
  return (
    <div className={styles.groupArtwork} aria-label="Illustrative Lisbon retreat group with four participants">
      <div className={styles.artTop}><span className={styles.artMicro}>GROUP / 01</span><span className={styles.artSpark} aria-hidden="true">✳</span></div>
      <div className={styles.groupTile}>
        <div className={styles.groupTileAccent} />
        <span className={styles.artMicro}>ACTIVE GROUP</span>
        <strong>Lisbon retreat<span>.</span></strong>
        <p>Product team · EUR</p>
        <div className={styles.groupMembers}>
          {previewGroup.participants.map((person, i) => <span data-index={i} key={person.name}>{person.initials}</span>)}
          <small>4 members</small>
        </div>
      </div>
      <div className={styles.artLabel}><span className={styles.littleDot} /> One group. Everyone accounted for.</div>
    </div>
  );
}

function ExpenseArtwork() {
  return (
    <div className={styles.expenseArtwork} aria-label="Illustrative €480 accommodation expense, split equally across four participants">
      <div className={styles.artTop}><span className={styles.artMicro}>EXPENSE / 04</span><span className={styles.artSpark} aria-hidden="true">✳</span></div>
      <div className={styles.expenseReceipt}>
        <div className={styles.receiptHeader}><span>ACCOMMODATION</span><Badge color="cobalt" size="xs">Equal split</Badge></div>
        <strong>€480<span>.00</span></strong>
        <small>Paid by Maya</small>
        <div className={styles.receiptDivider} />
        <div className={styles.splitVisual}>
          <div className={styles.splitDonut}><span>4<small>SHARES</small></span></div>
          <div className={styles.splitBreakdown}>
            <span><i /> Maya <b>€120</b></span>
            <span><i /> Noah <b>€120</b></span>
            <span><i /> Lena <b>€120</b></span>
            <span><i /> Ezra <b>€120</b></span>
          </div>
        </div>
      </div>
    </div>
  );
}

function SettlementArtwork() {
  return (
    <div className={styles.settlementArtwork} aria-label="Illustrative €80 external settlement recorded from Lena to Maya">
      <div className={styles.artTop}><span className={styles.artMicro}>SETTLEMENT / 02</span><span className={styles.artSpark} aria-hidden="true">✳</span></div>
      <div className={styles.settlementCard}>
        <span className={styles.settlementStatus}><span /> EXTERNALLY SETTLED</span>
        <strong>€80.00</strong>
        <div className={styles.transferPeople}>
          <div><span className={styles.personBubble}>LE</span><b>Lena</b><small>Sent</small></div>
          <div className={styles.transferLine}><span>→</span></div>
          <div><span className={styles.personBubble}>MA</span><b>Maya</b><small>Received</small></div>
        </div>
        <p><span aria-hidden="true">ⓘ</span> Recorded here. Money moved elsewhere.</p>
      </div>
    </div>
  );
}

export function PlatformStory() {
  return (
    <section id="platform" className={styles.platform} aria-labelledby="platform-heading">
      <div className={styles.sectionTop}>
        <div><SectionEyebrow number="01 /"> ONE CONNECTED PICTURE</SectionEyebrow><h2 id="platform-heading">A place for the people.<br /><em>A system for the numbers.</em></h2></div>
        <p>ShareWise connects the practical details of sharing costs without adding friction. A common space for groups, expenses, and the records that make balances understandable.</p>
      </div>
      <div className={styles.featureGrid}>
        <article className={styles.featureCard}>
          <GroupArtwork />
          <div className={styles.featureCopy}><span className={styles.featureIndex}>01 <span>/ PEOPLE</span></span><h3>Everyone in the picture.</h3><p>Keep participants together in a shared group, with a clear home for the costs that connect them.</p></div>
        </article>
        <article className={styles.featureCard}>
          <ExpenseArtwork />
          <div className={styles.featureCopy}><span className={styles.featureIndex}>02 <span>/ EXPENSES</span></span><h3>Every share, accounted for.</h3><p>Record who covered an expense and how its amount is allocated. Equal, fixed, or percentage splits, clearly represented.</p></div>
        </article>
        <article className={styles.featureCard}>
          <SettlementArtwork />
          <div className={styles.featureCopy}><span className={styles.featureIndex}>03 <span>/ SETTLEMENTS</span></span><h3>Close the loop, honestly.</h3><p>Log money exchanged outside the app, without confusing a recorded settlement with a payment ShareWise processed.</p></div>
        </article>
      </div>
    </section>
  );
}

export function HistoryStory() {
  return (
    <section className={styles.historySection} aria-labelledby="history-heading">
      <div className={styles.historyGrid}>
        <div className={styles.historyCopy}>
          <SectionEyebrow number="02 /"> BUILT FOR THE FULL STORY</SectionEyebrow>
          <h2 id="history-heading">Clarity today.<br /><em>Context forever.</em></h2>
          <p>Numbers change. The record should explain why. See the current group balance while preserving the revisions and external settlements that shaped it.</p>
          <div className={styles.historyPoints}>
            <div><span className={styles.checkIcon}>✓</span><span>Every expense correction leaves a trace.</span></div>
            <div><span className={styles.checkIcon}>✓</span><span>Past financial positions remain reviewable.</span></div>
            <div><span className={styles.checkIcon}>✓</span><span>Balances reflect the latest recorded state.</span></div>
          </div>
        </div>
        <div className={styles.historyVisual}>
          <div className={styles.historyHead}><span>FINANCIAL HISTORY</span><span>ILLUSTRATIVE RECORD · EUR</span></div>
          <div className={styles.historyContent}>
            <div className={styles.timelineItem}>
              <span className={styles.timelineGlyph}>↺</span>
              <div className={styles.timelineCopy}><span>NOV 13 · 10:42</span><strong>Accommodation revised</strong><p>Recorded by Maya · Revision 02</p>
                <div className={styles.revisionCompare}><div><span>PREVIOUS / V01</span><s>€460.00</s></div><span className={styles.compareArrow}>→</span><div><span>CURRENT / V02</span><b>€480.00</b></div></div>
              </div>
            </div>
            <div className={styles.timelineItem}>
              <span className={styles.timelineGlyph}>↗</span>
              <div className={styles.timelineCopy}><span>NOV 12 · 18:24</span><strong>External settlement recorded</strong><p>Lena sent €80.00 to Maya outside ShareWise</p></div>
            </div>
            <div className={styles.timelineItem}>
              <span className={styles.timelineGlyph}>↗</span>
              <div className={styles.timelineCopy}><span>NOV 12 · 17:10</span><strong>External settlement recorded</strong><p>Ezra sent €70.00 to Maya outside ShareWise</p></div>
            </div>
          </div>
          <div className={styles.historyFoot}><span className={styles.historyDot} /> PREVIOUS REVISIONS PRESERVED <span>03 EVENTS SHOWN</span></div>
        </div>
      </div>
      <span className={styles.historyGridline} aria-hidden="true" />
    </section>
  );
}

export function PrinciplesStory() {
  return (
    <>
      <section id="how-it-works" className={styles.steps} aria-labelledby="steps-heading">
        <div className={styles.stepsLead}><SectionEyebrow number="03 /"> SIMPLE BY DESIGN</SectionEyebrow><h2 id="steps-heading">Complex costs.<br /><em>Simple understanding.</em></h2></div>
        <div className={styles.stepsGrid}>
          <article><span className={styles.stepNum}>01 <span>↗</span></span><h3>Bring your group together.</h3><p>Create a shared context for the people involved, wherever life or work takes you.</p></article>
          <article><span className={styles.stepNum}>02 <span>↗</span></span><h3>Record the real expenses.</h3><p>Track who paid and what each person owes as a share of the total.</p></article>
          <article><span className={styles.stepNum}>03 <span>↗</span></span><h3>See the complete balance.</h3><p>Record outside settlements and understand current positions alongside their history.</p></article>
        </div>
      </section>
      <section id="principles" className={styles.principles} aria-labelledby="principles-heading">
        <div className={styles.principlesGlow} aria-hidden="true" />
        <div className={styles.principlesInner}>
          <div className={styles.principlesSymbol} aria-hidden="true">✳</div>
          <SectionEyebrow number="04 /"> THE SHAREWISE PRINCIPLE</SectionEyebrow>
          <h2 id="principles-heading">We don&apos;t move the money.<br /><span>We make it make sense.</span></h2>
          <p>ShareWise is a shared financial record, not a payment processor. You handle payments your way. We help everyone understand the numbers, the context, and the changes.</p>
          <Button component="a" href="#top" size="lg" color="cobalt" variant="white" rightSection={<span aria-hidden="true">↑</span>}>Back to the beginning</Button>
        </div>
        <div className={styles.principlesFoot}><span>DESIGNED FOR CLARITY</span><span>NOT FOR MOVING MONEY</span></div>
      </section>
    </>
  );
}

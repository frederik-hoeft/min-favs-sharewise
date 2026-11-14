import { Badge } from "@mantine/core";
import { members } from "./illustrative-ledger";
import { MemberAvatar } from "@/components/member-avatar";
import styles from "./cost-story.module.scss";

/** Editorial fragments of the same fictional group, not interactive expense controls. */
export function CostStory() {
  return (
    <div className={styles.grid} aria-label="Illustrative expense and settlement previews">
      <article className={styles.allocationCard}>
        <div className={styles.cardTop}><span className={styles.tag}>01 / FAIR SHARES</span><Badge color="pine" variant="light">Expense example</Badge></div>
        <div className={styles.allocationHead}><span className={styles.dinnerGlyph} aria-hidden="true">✳</span><div><span>WEEKEND IN COPENHAGEN</span><h3>Dinner together</h3><p>Paid by Leila Ortiz</p></div><strong>€164<span>.00</span></strong></div>
        <div className={styles.splitHeader}><span>Allocated equally</span><span>€41 per person</span></div>
        <div className={styles.splitRows}>
          {members.map((member) => (
            <div className={styles.splitRow} key={member.initials}>
              <MemberAvatar initials={member.initials} tone={member.tone} small />
              <span>{member.name}{member.name === "Leila Ortiz" ? <small> · Payer</small> : null}</span>
              <strong>€41.00</strong>
            </div>
          ))}
        </div>
        <div className={styles.allocationBottom}><span>4 participants</span><span>Total allocated <strong>€164.00</strong></span></div>
      </article>
      <article className={styles.settlementCard}>
        <span className={styles.tag}>02 / THE REAL WORLD</span>
        <div className={styles.settlementIcon} aria-hidden="true">↗</div>
        <h3>Settled outside.<br /><em>Remembered here.</em></h3>
        <p>ShareWise records what your group reports. The money itself moves between people, not through the app.</p>
        <div className={styles.settlementPaper}>
          <div className={styles.receiptTop}><span>RECORDED EXTERNAL SETTLEMENT</span><span>✓</span></div>
          <div className={styles.transfers}>
            <div className={styles.transferPerson}><MemberAvatar initials="JW" tone="sand" small /><span>Jonas<small>Sent</small></span></div>
            <span className={styles.transferArrow} aria-hidden="true">→</span>
            <div className={styles.transferPerson}><MemberAvatar initials="MC" tone="sage" small /><span>Maya<small>Received</small></span></div>
            <strong>€50</strong>
          </div>
          <div className={styles.receiptFoot}>Recorded in group history <span>Not a payment confirmation</span></div>
        </div>
      </article>
    </div>
  );
}

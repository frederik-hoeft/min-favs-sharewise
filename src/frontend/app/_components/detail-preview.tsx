import { Badge } from "@mantine/core";
import { example } from "../_data/example";
import styles from "./detail-preview.module.scss";

/** Expense revision and its materialized allocation, kept as read-only illustration. */
export function DetailPreview() {
  return (
    <section className={styles.panel} aria-label="Illustrative expense and allocation details">
      <div className={styles.panelTop}><span>EXPENSE DETAIL</span><Badge variant="light" color="copper" size="sm" radius="sm">REVISION {example.expenses[0].revision}</Badge></div>
      <p className={styles.tinyLabel}>THE LAKE HOUSE / EXPENSES</p>
      <div className={styles.headline}><h3>{example.expenses[0].name}</h3><span>{example.expenses[0].amount}</span></div>
      <p className={styles.meta}>Paid by {example.expenses[0].payer} <span>·</span> {example.expenses[0].split} allocation <span>·</span> 4 people</p>
      <div className={styles.rule} />
      <div className={styles.splitHeader}><strong>Cost allocation</strong><span>Each share</span></div>
      <div className={styles.bars} aria-hidden="true"><i /><i /><i /><i /></div>
      <div className={styles.allocation}>
        {example.allocation.map(({ name, share }) => (
          <div className={styles.share} key={name}><span>{name}</span><strong>{share}</strong></div>
        ))}
      </div>
      <p className={styles.footer}>Four shares add up to the full {example.expenses[0].amount}.</p>
    </section>
  );
}

/** Group-local financial revisions; no interaction or point-in-time queries. */
export function HistoryPreview() {
  return (
    <section className={styles.history} aria-label="Illustrative group financial history">
      <div className={styles.historyHeading}>
        <div><span className={styles.tinyLabel}>FINANCIAL RECORD</span><h3>Every change, in context.</h3></div>
        <span className={styles.revisionTag}>CURRENT · R06</span>
      </div>
      <div className={styles.timeline}>
        {example.history.map((item) => (
          <div className={styles.event} key={item.revision}>
            <span className={styles.eventMark} aria-hidden="true" />
            <div className={styles.eventBody}>
              <div className={styles.eventHeading}><strong>{item.action}</strong><time>{item.date}</time></div>
              <p>{item.detail}</p>
            </div>
            <span className={styles.revisionNumber}>R{item.revision}</span>
          </div>
        ))}
      </div>
      <div className={styles.historyNote}><span aria-hidden="true">◷</span> Each group has its own ordered financial history.</div>
    </section>
  );
}

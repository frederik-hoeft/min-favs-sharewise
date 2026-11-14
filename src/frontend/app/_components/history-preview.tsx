import { Badge } from "@mantine/core";
import styles from "./history-preview.module.scss";

const entries = [
  { revision: "005", date: "12 SEP 2026", kind: "Settlement recorded", detail: "Mina → Clara · €100.00", tag: "NEW" },
  { revision: "004", date: "11 SEP 2026", kind: "Groceries revised", detail: "€220.00 → €240.00 · revision 2", tag: "REVISED" },
  { revision: "003", date: "10 SEP 2026", kind: "Car rental recorded", detail: "Paid by Noah · €320.00", tag: "NEW" },
] as const;

/** An append-only-looking editorial ledger, not an interactive financial-history view. */
export function HistoryPreview() {
  return (
    <figure className={styles.figure} aria-label="Illustrative financial change history">
      <div className={styles.header}>
        <div><span>THE COAST HOUSE / EUR</span><h3>Change history</h3></div>
        <Badge color="gray" variant="outline" size="sm">Illustrative</Badge>
      </div>
      <div className={styles.columnHeads}><span>REV.</span><span>CHANGE</span><span>DATE</span></div>
      <ol className={styles.entries}>
        {entries.map((entry) => (
          <li key={entry.revision} className={styles.entry}>
            <span className={styles.revision}>{entry.revision}</span>
            <div className={styles.details}><div><strong>{entry.kind}</strong><span className={styles.tag}>{entry.tag}</span></div><p>{entry.detail}</p></div>
            <time>{entry.date}</time>
          </li>
        ))}
      </ol>
      <figcaption>Earlier changes remain visible after corrections. Latest financial positions use current revisions.</figcaption>
    </figure>
  );
}

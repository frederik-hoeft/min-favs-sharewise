import { Badge, Card } from "@mantine/core";
import { BalanceValue } from "@/components/balance-value/BalanceValue";
import { example } from "./preview-data";
import styles from "./HistoryPreview.module.scss";

export function HistoryPreview() {
  return (
    <Card className={styles.card} aria-label="Illustrative financial change history">
      <div className={styles.topline}>
        <span className={styles.overline}>FINANCIAL HISTORY</span>
        <Badge color="gray">Group revision 05</Badge>
      </div>
      <h3>Nothing gets lost in the edits.</h3>
      <p className={styles.intro}>See what was recorded, corrected, and settled externally.</p>
      <ol className={styles.timeline}>
        <li>
          <span className={styles.revision}>05</span>
          <div>
            <strong>Settlement recorded</strong>
            <p>Morgan sent €10.00 to Sam <em>outside ShareWise</em>.</p>
          </div>
        </li>
        <li>
          <span className={styles.revision}>04</span>
          <div>
            <strong>Expense corrected</strong>
            <p>Cabin supplies · €48.00 → €54.00</p>
          </div>
        </li>
        <li>
          <span className={styles.revision}>03</span>
          <div>
            <strong>Expense recorded</strong>
            <p>Dinner · Sam paid €90.00</p>
          </div>
        </li>
      </ol>
      <div className={styles.snapshot}>
        <div className={styles.snapshotTop}>
          <strong>Historical balances</strong>
          <span>At revision 03</span>
        </div>
        <div className={styles.snapshotValues}>
          {example.historicalBalances.map((member) => (
            <div key={member.name}>
              <span>{member.name}</span>
              <strong><BalanceValue value={member.value} tone={member.tone} /></strong>
            </div>
          ))}
        </div>
      </div>
    </Card>
  );
}

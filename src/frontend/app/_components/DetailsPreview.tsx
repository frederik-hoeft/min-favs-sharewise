import { Badge } from "@mantine/core";
import { displayMoney, previewGroup } from "./preview-data";
import styles from "./DetailsPreview.module.scss";

export function DetailsPreview() {
  return (
    <div className={styles.grid} aria-label="Illustrative expense and history views">
      <article className={styles.expenses}>
        <div className={styles.panelHead}>
          <span className={styles.miniLabel}>THE LEDGER</span>
          <Badge variant="light" color="indigoBrand">3 expenses</Badge>
        </div>
        <h3>Every expense, in its place.</h3>
        <p className={styles.description}>See who paid, what it covered, and how the cost was shared.</p>
        <div className={styles.ledger}>
          <div className={styles.ledgerHeader}><span>Expense</span><span>Amount</span></div>
          {previewGroup.expenses.map((expense, index) => (
            <div key={expense.title} className={styles.expenseRow}>
              <span className={styles.expenseIcon} aria-hidden="true">{["⌂", "◈", "▤"][index]}</span>
              <div className={styles.expenseDescription}>
                <strong>{expense.title}</strong>
                <span>Paid by {expense.paidBy} · split equally</span>
              </div>
              <div className={styles.expenseAmount}>
                <strong>{displayMoney(expense.amountCents)}</strong>
                <span>{expense.date}</span>
              </div>
            </div>
          ))}
          <div className={styles.allocation}>
            <span className={styles.allocationIcon} aria-hidden="true">÷</span>
            <div>
              <strong>Clear, equal allocations</strong>
              <span>Each participant’s share: €170 across these expenses.</span>
            </div>
          </div>
        </div>
      </article>

      <div className={styles.side}>
        <article className={styles.settlement}>
          <span className={styles.miniLabel}>OUTSIDE THE APP</span>
          <h3>Paid in real life. Recorded here.</h3>
          <p className={styles.description}>No transfers through ShareWise. Just an accurate record of what your group reports.</p>
          <div className={styles.transfer}>
            <div className={styles.transferAvatars}><span>NO</span><span>MY</span></div>
            <div className={styles.transferDescription}><strong>{previewGroup.settlement.sender} paid {previewGroup.settlement.recipient}</strong><span>External settlement · recorded {previewGroup.settlement.date}</span></div>
            <strong className={styles.transferAmount}>{displayMoney(previewGroup.settlement.amountCents)}</strong>
          </div>
        </article>
        <article className={styles.history}>
          <span className={styles.miniLabel}>A HISTORY THAT STAYS</span>
          <h3>Clarity, even when plans change.</h3>
          <div className={styles.timeline}>
            <div className={styles.event}><span className={styles.timelineDot} /><div><strong>Museum tickets added</strong><span>Nov 9 · Group revision 5</span></div></div>
            <div className={styles.event}><span className={styles.timelineDot} /><div><strong>External settlement recorded</strong><span>Nov 8 · Group revision 4</span></div></div>
            <div className={styles.event}><span className={styles.timelineDot} /><div><strong>Dinner corrected · €100 → €120</strong><span>Nov 7 · Expense v2 · Group revision 3</span></div></div>
          </div>
          <p className={styles.historyFooter}>Earlier revisions and historical balances remain reviewable.</p>
        </article>
      </div>
    </div>
  );
}

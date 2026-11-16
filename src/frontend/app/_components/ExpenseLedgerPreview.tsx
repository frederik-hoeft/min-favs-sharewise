import { Badge, Card, Paper, Title } from "@mantine/core";
import { displayMoney, previewGroup } from "./preview-data";
import styles from "./ExpenseLedgerPreview.module.scss";

const expenseSymbols = ["⌂", "◈", "▤"] as const;

export function ExpenseLedgerPreview() {
  return (
    <Card component="article" className={styles.panel}>
      <div className={styles.panelHead}>
        <span className={styles.kicker}>THE LEDGER</span>
        <Badge variant="light">3 expenses</Badge>
      </div>
      <Title order={3} className={styles.title}>Every expense, in its place.</Title>
      <p className={styles.description}>See who paid, what it covered, and how the cost was shared.</p>
      <Paper withBorder radius="md" className={styles.ledger}>
        <div className={styles.ledgerHeader}><span>Expense</span><span>Amount</span></div>
        {previewGroup.expenses.map((expense, index) => (
          <div key={expense.title} className={styles.expenseRow}>
            <span className={styles.expenseIcon} aria-hidden="true">{expenseSymbols[index]}</span>
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
      </Paper>
    </Card>
  );
}

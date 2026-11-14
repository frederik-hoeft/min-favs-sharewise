import { Badge } from "@mantine/core";
import { formatMoney } from "@/lib/format-money";
import styles from "./expense-detail.module.scss";

const allocations = ["Clara", "Noah", "Mina", "Leo"] as const;

/** Illustrative record detail, not a form or an expense editor. */
export function ExpenseDetail() {
  return (
    <figure className={styles.sheet} aria-label="Illustrative expense allocation for cabin rental">
      <div className={styles.header}>
        <span>EXPENSE / 01</span>
        <Badge variant="outline" color="gray" size="sm">Recorded</Badge>
      </div>
      <div className={styles.subject}>
        <div><span className={styles.label}>Expense detail</span><h3>Cabin rental</h3></div>
        <strong>{formatMoney(84000, "EUR")}</strong>
      </div>
      <div className={styles.meta}>
        <div><span>Paid by</span><strong>Clara</strong></div>
        <div><span>Allocation</span><strong>Equal · 4 people</strong></div>
        <div><span>Currency</span><strong>EUR</strong></div>
      </div>
      <div className={styles.allocationHeader}><h4>Allocated shares</h4><span>4 / 4 participants</span></div>
      <div className={styles.rows}>
        {allocations.map((name, index) => (
          <div key={name} className={styles.row}>
            <span className={styles.index}>0{index + 1}</span>
            <span>{name}</span>
            <strong>{formatMoney(21000, "EUR")}</strong>
          </div>
        ))}
      </div>
      <div className={styles.total}><span>Allocated total</span><strong>{formatMoney(84000, "EUR")}</strong></div>
      <figcaption>Illustrative record / all four shares add up to the expense total.</figcaption>
    </figure>
  );
}

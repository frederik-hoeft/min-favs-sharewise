import { ExpenseLedgerPreview } from "./ExpenseLedgerPreview";
import { SettlementPreview } from "./SettlementPreview";
import { HistoryPreview } from "./HistoryPreview";
import styles from "./DetailsPreview.module.scss";

/** The landing page owns this composition; the panels own their illustrative content. */
export function DetailsPreview() {
  return (
    <div className={styles.grid} aria-label="Illustrative expense and history views">
      <ExpenseLedgerPreview />
      <div className={styles.side}>
        <SettlementPreview />
        <HistoryPreview />
      </div>
    </div>
  );
}

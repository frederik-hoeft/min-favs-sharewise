import { Badge } from "@mantine/core";
import { activity, pastBalances, participants } from "../_data/illustrative-ledger";
import { BalanceList } from "@/components/finance/balance-list";
import styles from "./history-preview.module.scss";

export function HistoryPreview() {
  return (
    <div className={styles.history} aria-label="Illustrative change history and past balances">
      <div className={styles.top}><div><span className={styles.eyebrow}>GROUP JOURNAL</span><h3>The full story, in order.</h3></div><Badge color="citron.8" variant="filled">Revision 05</Badge></div>
      <ol className={styles.timeline}>
        {activity.map((item) => (
          <li key={item.id}>
            <span className={styles.node} aria-hidden="true" />
            <div className={styles.event}><span className={styles.revision}>R{String(item.id).padStart(2, "0")}</span><div><strong>{item.label}</strong><span>{item.detail}</span><small>{item.date}</small></div></div>
          </li>
        ))}
      </ol>
      <div className={styles.snapshot}>
        <div className={styles.snapshotHeader}><strong>Earlier group position</strong><span>At revision 03</span></div>
        <BalanceList balances={pastBalances} participants={participants} label="Group positions at revision three" />
        <p>Before the dinner correction and recorded external settlement.</p>
      </div>
    </div>
  );
}

import { Badge } from "@mantine/core";
import { changes } from "./illustrative-ledger";
import styles from "./history-preview.module.scss";

export function HistoryPreview() {
  return (
    <div className={styles.panel} aria-label="Illustrative financial change history">
      <div className={styles.panelHead}>
        <div><span>WEEKEND IN COPENHAGEN / HISTORY</span><h3>Nothing gets lost<br />in the edits.</h3></div>
        <Badge color="pine" variant="light" size="lg">Group revision 12</Badge>
      </div>
      <ol className={styles.timeline}>
        {changes.map((change, i) => (
          <li key={change.revision} className={styles.event}>
            <span className={styles.node}>{i === 0 ? "✳" : i === 1 ? "↗" : "+"}</span>
            <span className={styles.eventContent}><small>{change.kind}</small><strong>{change.title}</strong><span>{change.detail}</span></span>
            <span className={styles.revision}>REV {change.revision}</span>
          </li>
        ))}
      </ol>
      <div className={styles.panelFooter}><span>Every recorded change belongs to the group&apos;s financial timeline.</span><span aria-hidden="true">↗</span></div>
    </div>
  );
}

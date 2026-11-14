import { Badge } from "@mantine/core";
import { previewGroup } from "../_data/preview";
import styles from "./history-preview.module.scss";

/** Illustrative group-local revision journal. Earlier revisions remain visible. */
export function HistoryPreview() {
  return (
    <div className={styles.card} aria-label="Illustrative financial change history">
      <div className={styles.topline}>
        <span className={styles.microLabel}>FINANCIAL HISTORY</span>
        <Badge variant="light" color="ocean">Current · r{previewGroup.history[0].revision}</Badge>
      </div>
      <h3>Every change, accounted for.</h3>
      <p className={styles.subtitle}>Cabin retreat · group financial revisions</p>
      <ol className={styles.timeline}>
        {previewGroup.history.map((item) => (
          <li className={styles.event} key={item.revision}>
            <div className={`${styles.eventSymbol} ${styles[item.kind]}`} aria-hidden="true">
              {item.kind === "revision" ? "↺" : item.kind === "settlement" ? "↗" : "+"}
            </div>
            <div className={styles.eventContent}>
              <div className={styles.eventHead}>
                <strong>{item.description}</strong>
                <span>{item.date}</span>
              </div>
              <p><span>{item.actor}</span> · {item.detail}</p>
            </div>
            <span className={styles.revision}>r{item.revision}</span>
          </li>
        ))}
      </ol>
      <div className={styles.bottomNote}>
        <span className={styles.noteIcon} aria-hidden="true">↺</span>
        <div>
          <strong>Past revisions stay part of the record.</strong>
          <p>Review financial positions as they stood at earlier group revisions.</p>
        </div>
      </div>
    </div>
  );
}

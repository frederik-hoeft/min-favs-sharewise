import styles from "./history-preview.module.scss";

/** Literal examples of immutable, group-local financial journal revisions. */
export function HistoryPreview() {
  return (
    <figure className={styles.figure} aria-label="Illustrative group financial history">
      <div className={styles.panel}>
        <div className={styles.head}><span className={styles.eyebrow}>CABIN WEEKEND / HISTORY</span><span className={styles.badge}>5 revisions</span></div>
        <h3>Every change, in context.</h3>
        <p className={styles.subtitle}>A record of what changed, and when.</p>
        <ol className={styles.events}>
          <li>
            <span className={styles.timelineDot} aria-hidden="true" />
            <div className={styles.entryTop}><strong>External settlement recorded</strong><span>REV. 05</span></div>
            <p>Dani → Ben · €40.00 <span className={styles.external}>Outside ShareWise</span></p>
            <small>Recorded on 16 September · Group balance updated</small>
          </li>
          <li>
            <span className={styles.timelineDot} aria-hidden="true" />
            <div className={styles.entryTop}><strong>Groceries revised</strong><span>REV. 04</span></div>
            <p>Expense total corrected <s>€112.00</s> <strong>€120.00</strong></p>
            <small>15 September · Earlier revision preserved</small>
          </li>
          <li>
            <span className={styles.timelineDot} aria-hidden="true" />
            <div className={styles.entryTop}><strong>Road snacks added</strong><span>REV. 03</span></div>
            <p>€40.00 · Paid by Cleo · Equal allocation</p>
            <small>14 September · Initial expense record</small>
          </li>
        </ol>
        <div className={styles.snapshot}>
          <div className={styles.snapshotIcon} aria-hidden="true">◷</div>
          <div><strong>History that adds up</strong><span>See financial positions as they stood at a group revision.</span></div>
          <span className={styles.snapshotArrow} aria-hidden="true">↗</span>
        </div>
      </div>
      <figcaption>Illustrative timeline · revisions are not editable in this preview</figcaption>
    </figure>
  );
}

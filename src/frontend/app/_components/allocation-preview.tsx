import { exampleGroup } from "./illustration-data";
import styles from "./allocation-preview.module.scss";

/** Illustrates one stored expense revision and its allocations, not an editor. */
export function AllocationPreview() {
  return (
    <figure className={styles.figure} aria-label="Illustrative equal allocation of cabin rental">
      <div className={styles.panel}>
        <div className={styles.topbar}><span className={styles.chip}>EXPENSE DETAIL</span><span className={styles.revision}>Revision 1</span></div>
        <div className={styles.titleRow}><div><p>CABIN WEEKEND</p><h3>Cabin rental</h3><span>Paid by Ben · Equal split</span></div><strong>€240<span>.00</span></strong></div>
        <div className={styles.divider} />
        <div className={styles.allocationHeading}><span>Allocated to</span><span>€60.00 each</span></div>
        <div className={styles.allocationTrack} role="img" aria-label="Four equal shares"><span /><span /><span /><span /></div>
        <div className={styles.people}>
          {exampleGroup.participants.map((person) => (
            <div className={styles.person} key={person.name}>
              <span className={`${styles.avatar} ${styles[person.tone]}`}>{person.initials}</span>
              <span>{person.name}</span><strong>€60.00</strong>
            </div>
          ))}
        </div>
        <div className={styles.footnote}><span className={styles.check} aria-hidden="true">✓</span> All €240.00 allocated across four participants</div>
      </div>
      <figcaption>Illustrative expense record · not an interactive form</figcaption>
    </figure>
  );
}

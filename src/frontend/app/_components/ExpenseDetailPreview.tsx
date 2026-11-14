import { Avatar, Badge, Card } from "@mantine/core";
import { example } from "./preview-data";
import styles from "./ExpenseDetailPreview.module.scss";

export function ExpenseDetailPreview() {
  return (
    <Card className={styles.card} aria-label="Illustrative expense and allocation details">
      <div className={styles.topline}>
        <span className={styles.overline}>EXPENSE DETAILS</span>
        <Badge color="gray">Revision 2</Badge>
      </div>
      <div className={styles.titleRow}>
        <div>
          <h3>Cabin supplies</h3>
          <p>Paid by Morgan · Equal split</p>
        </div>
        <strong>€54.00</strong>
      </div>
      <div className={styles.divider} />
      <div className={styles.allocationHeading}>
        <h4>Cost allocation</h4>
        <span>{example.cabinAllocation.length} participants</span>
      </div>
      <div className={styles.allocationList}>
        {example.cabinAllocation.map((participant) => (
          <div className={styles.allocation} key={participant.name}>
            <Avatar color="brand" size="sm" radius="xl" variant="light">
              {participant.initials}
            </Avatar>
            <span>{participant.name}</span>
            <span className={styles.shareType}>Equal share</span>
            <strong>{participant.value}</strong>
          </div>
        ))}
      </div>
      <div className={styles.footnote}>
        <span className={styles.noteDot} aria-hidden="true" />
        Each recorded share belongs to this expense revision.
      </div>
    </Card>
  );
}

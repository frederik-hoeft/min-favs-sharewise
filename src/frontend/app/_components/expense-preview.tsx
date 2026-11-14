import { Avatar, AvatarGroup, Badge } from "@mantine/core";
import { previewGroup } from "../_data/preview";
import styles from "./expense-preview.module.scss";

/** Illustrative expense allocation, with deliberately precomputed fixture amounts. */
export function ExpensePreview() {
  const expense = previewGroup.expenses[0];

  return (
    <div className={styles.card} aria-label="Illustrative shared expense allocation">
      <div className={styles.cardHeading}>
        <span className={styles.label}>EXPENSE DETAIL</span>
        <Badge color="ocean" variant="light" size="md">Equal split</Badge>
      </div>
      <div className={styles.expenseTitle}>
        <div className={styles.expenseIcon} aria-hidden="true">
          <svg width="29" height="29" viewBox="0 0 32 32" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round">
            <path d="M3 14 16 4l13 10M7 12v16h18V12M13 28V18h6v10" />
          </svg>
        </div>
        <div>
          <h3>{expense.name}</h3>
          <p>Paid by {expense.payer} · {previewGroup.name}</p>
        </div>
        <strong>{expense.amount}</strong>
      </div>
      <div className={styles.divider} />
      <div className={styles.allocationHeading}>
        <div>
          <h4>Cost allocation</h4>
          <p>One clear share for everyone</p>
        </div>
        <span>{previewGroup.participants.length} × {expense.share}</span>
      </div>
      <div className={styles.allocationBars} aria-hidden="true">
        {previewGroup.participants.map((participant) => (
          <span key={participant.initials} className={styles.bar} />
        ))}
      </div>
      <div className={styles.participantRows}>
        {previewGroup.participants.map((participant) => (
          <div key={participant.initials} className={styles.participantRow}>
            <span className={styles.avatarSmall}>{participant.initials}</span>
            <span>{participant.name}</span>
            <span className={styles.percentage}>25%</span>
            <strong>{expense.share}</strong>
          </div>
        ))}
      </div>
      <div className={styles.allocationFooter}>
        <span>Allocated in full</span>
        <strong>{expense.amount} / {expense.amount}</strong>
      </div>
    </div>
  );
}

export function SettlementPreview() {
  const { settlement, participants } = previewGroup;
  return (
    <div className={styles.settlementCard} aria-label="Illustrative external settlement record">
      <div className={styles.cardHeading}>
        <span className={styles.label}>RECORDED SETTLEMENT</span>
        <span className={styles.recordedDot}>Recorded</span>
      </div>
      <div className={styles.settlementAmount}>{settlement.amount}</div>
      <p className={styles.settlementSubtitle}>{settlement.note}</p>
      <div className={styles.transferPeople}>
        <div className={styles.transferPerson}><span>CL</span><strong>{settlement.sender}</strong><small>Sent</small></div>
        <div className={styles.transferArrow} aria-hidden="true">→</div>
        <div className={styles.transferPerson}><span>AV</span><strong>{settlement.recipient}</strong><small>Received</small></div>
      </div>
      <p className={styles.settlementNote}>ShareWise records this transfer. It doesn’t send or verify payments.</p>
      <div className={styles.groupFooter}>
        <div>
          <strong>All your people, together.</strong>
          <span>Four participants in the group</span>
        </div>
        <AvatarGroup spacing="sm">
          {participants.map((person) => <Avatar key={person.initials} name={person.name} color={person.avatarColor} variant="filled" size="sm" />)}
        </AvatarGroup>
      </div>
    </div>
  );
}

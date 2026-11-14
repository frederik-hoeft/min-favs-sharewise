import { Avatar, Badge, Card } from "@mantine/core";
import { participants } from "../_data/illustrative-ledger";
import styles from "./expense-preview.module.scss";

export function ExpensePreview() {
  return (
    <div className={styles.scene} aria-label="Illustrative expense allocation details">
      <span className={styles.cornerTag}>EXPENSE / REVISION 02</span>
      <Card className={styles.card}>
        <div className={styles.titleRow}><span className={styles.receiptIcon} aria-hidden="true">◒</span><Badge color="pine">Equal split</Badge></div>
        <p className={styles.label}>SUNDAY TABLE / FOOD & DRINK</p>
        <h3>Dinner at Flora</h3>
        <p className={styles.amount}>€66<span>.00</span></p>
        <div className={styles.payer}><Avatar color="clay" size="sm" radius="xl">NO</Avatar><span>Paid by <strong>Noah</strong></span></div>
        <div className={styles.divider} />
        <div className={styles.splitHeading}><strong>Split three ways</strong><span>€22.00 each</span></div>
        <div className={styles.splitBar} aria-hidden="true"><span /><span /><span /></div>
        <ul className={styles.allocations}>
          {participants.map((person) => (
            <li key={person.id}><span className={styles.person}><Avatar color={person.color} variant="light" size="sm" radius="xl">{person.initials}</Avatar>{person.name}</span><span>€22.00</span></li>
          ))}
        </ul>
        <p className={styles.footerNote}>An expense revision preserves its original record.</p>
      </Card>
      <div className={styles.floatNote}><span className={styles.spark} aria-hidden="true">✳</span><strong>Everyone knows their share.</strong><span>One currency. One clear record.</span></div>
    </div>
  );
}

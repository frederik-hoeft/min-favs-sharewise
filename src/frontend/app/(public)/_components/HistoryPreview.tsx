import { Card, Title } from "@mantine/core";
import styles from "./HistoryPreview.module.scss";

const events = [
  { title: "Museum tickets added", metadata: "Nov 9 · Group revision 5" },
  { title: "External settlement recorded", metadata: "Nov 8 · Group revision 4" },
  { title: "Dinner corrected · €100 → €120", metadata: "Nov 7 · Expense v2 · Group revision 3" },
] as const;

export function HistoryPreview() {
  return (
    <Card component="article" className={styles.panel}>
      <span className={styles.kicker}>A HISTORY THAT STAYS</span>
      <Title order={3} className={styles.title}>Clarity, even when plans change.</Title>
      <div className={styles.timeline}>
        {events.map((event) => (
          <div className={styles.event} key={event.title}>
            <span className={styles.timelineDot} aria-hidden="true" />
            <div><strong>{event.title}</strong><span>{event.metadata}</span></div>
          </div>
        ))}
      </div>
      <p className={styles.historyFooter}>Earlier revisions and historical balances remain reviewable.</p>
    </Card>
  );
}

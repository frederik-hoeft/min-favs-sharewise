import { Avatar } from "@mantine/core";
export type ParticipantDisplay = {
  id: string;
  name: string;
  initials: string;
  color: "pine" | "clay" | "grape";
};

export type BalanceDisplay = {
  participantId: string;
  amount: string;
  direction: "owed" | "owes";
};
import styles from "./balance-list.module.scss";

/** Read-only group financial positions; positive values are receivables, not payments. */
export function BalanceList({ balances, participants, label }: { balances: readonly BalanceDisplay[]; participants: readonly ParticipantDisplay[]; label: string }) {
  return (
    <ul className={styles.list} aria-label={label}>
      {balances.map((balance) => {
        const person = participants.find((participant) => participant.id === balance.participantId);
        if (!person) return null;
        return (
          <li key={person.id} className={styles.item}>
            <span className={styles.person}>
              <Avatar size="sm" radius="xl" color={person.color} variant="light">
                {person.initials}
              </Avatar>
              <span>{person.name}</span>
            </span>
            <span className={`${styles.amount} ${balance.direction === "owed" ? styles.credit : styles.debit}`}>
              {balance.amount}<span className={styles.description}>{balance.direction === "owed" ? "is owed" : "owes"}</span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}

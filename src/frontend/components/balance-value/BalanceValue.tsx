import styles from "./BalanceValue.module.scss";

type BalanceTone = "positive" | "negative" | "neutral";

/** Semantic balance presentation. No financial calculations or data access. */
export function BalanceValue({
  value,
  tone,
}: {
  value: string;
  tone: BalanceTone;
}) {
  return <span className={styles[tone]}>{value}</span>;
}

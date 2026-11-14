import styles from "./brand.module.scss";

/** The mark is an abstract pair of balanced ledger entries. */
export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <span className={styles.brand}>
      <svg className={styles.mark} viewBox="0 0 46 46" aria-hidden="true" focusable="false">
        <rect x="1" y="1" width="44" height="44" rx="14" fill="currentColor" />
        <path d="M12 16h22M12 23h13M21 30h13" stroke="var(--sw-brand-mark)" strokeWidth="3" strokeLinecap="round" />
        <circle cx="30.5" cy="23" r="3.5" fill="var(--sw-brand-mark)" />
        <circle cx="15.5" cy="30" r="3.5" fill="var(--sw-brand-mark)" />
      </svg>
      <span className={styles.wordmark}>Share<span>Wise</span>{!compact && <span className={styles.period}>.</span>}</span>
    </span>
  );
}

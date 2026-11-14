import styles from "./brand-mark.module.scss";

/** Shared wordmark. Decorative geometry represents three connected nodes in a shared ledger. */
export function BrandMark() {
  return (
    <span className={styles.lockup}>
      <span className={styles.mark} aria-hidden="true">
        <svg viewBox="0 0 40 40" fill="none" focusable="false">
          <path d="M10 12.5 20 7l10 5.5v15L20 33l-10-5.5v-15Z" stroke="currentColor" strokeWidth="2.6" strokeLinejoin="round" />
          <path d="m10.5 13 9.5 6 9.5-6M20 19v13" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" />
          <circle cx="10" cy="12.5" r="3" fill="currentColor" />
          <circle cx="30" cy="12.5" r="3" fill="currentColor" />
          <circle cx="20" cy="33" r="3" fill="currentColor" />
        </svg>
      </span>
      <span className={styles.name}>share<span>wise</span></span>
    </span>
  );
}

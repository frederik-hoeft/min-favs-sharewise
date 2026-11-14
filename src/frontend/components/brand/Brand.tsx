import styles from "./Brand.module.scss";

/** Brand lockup usable on the public site and inside the eventual app shell. */
export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <span className={styles.brand}>
      <span className={styles.mark} aria-hidden="true">
        <svg viewBox="0 0 36 36" fill="none" focusable="false">
          <path d="M10 11h10.5a4.5 4.5 0 0 1 4.5 4.5V25" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" />
          <path d="M10 20.5V25h6" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" />
          <circle cx="10" cy="11" r="3" fill="currentColor" />
          <circle cx="25" cy="25" r="3" fill="currentColor" />
        </svg>
      </span>
      {!compact && <span className={styles.wordmark}>ShareWise</span>}
    </span>
  );
}

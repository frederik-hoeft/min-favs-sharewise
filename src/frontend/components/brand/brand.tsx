import styles from "./brand.module.scss";

export function Brand() {
  return (
    <span className={styles.brand}>
      <svg className={styles.mark} width="37" height="37" viewBox="0 0 37 37" fill="none" aria-hidden="true">
        <rect x="0" y="0" width="37" height="37" rx="12" fill="currentColor" />
        <path d="M9 17.5c5.1 0 7.3-4.1 9.5-8.2 2.2 4.1 4.4 8.2 9.5 8.2-5.1 0-7.3 4.1-9.5 10-2.2-5.9-4.4-10-9.5-10Z" fill="var(--sw-highlight)" />
        <circle cx="18.5" cy="18.5" r="3" fill="currentColor" />
      </svg>
      <span>sharewise<span className={styles.period}>.</span></span>
    </span>
  );
}

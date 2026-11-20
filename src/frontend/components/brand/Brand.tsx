import styles from "./Brand.module.scss";

/** Reusable wordmark; callers own the surrounding navigation or layout. */
export function Brand({ symbolOnly = false, compactOnNarrow = false }: { symbolOnly?: boolean; compactOnNarrow?: boolean }) {
  return (
    <span className={`${styles.brand} ${compactOnNarrow ? styles.compactOnNarrow : ""}`} aria-label="ShareWise">
      <span className={styles.symbol} aria-hidden="true">
        <span />
        <span />
      </span>
      {!symbolOnly && <span className={styles.wordmark}>sharewise<span className={styles.period}>.</span></span>}
    </span>
  );
}

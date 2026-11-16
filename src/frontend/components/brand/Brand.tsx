import styles from "./Brand.module.scss";

/** Reusable wordmark; callers own the surrounding navigation or layout. */
export function Brand() {
  return (
    <span className={styles.brand} aria-label="ShareWise">
      <span className={styles.symbol} aria-hidden="true">
        <span />
        <span />
      </span>
      <span className={styles.wordmark}>sharewise<span className={styles.period}>.</span></span>
    </span>
  );
}

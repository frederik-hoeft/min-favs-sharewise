import styles from "./brand.module.scss";

/** Portable wordmark for public and authenticated ShareWise shells. */
export function Brand() {
  return (
    <span className={styles.brand}>
      <span className={styles.symbol} aria-hidden="true"><i /><i /><i /><i /></span>
      <span>sharewise<span className={styles.period}>.</span></span>
    </span>
  );
}

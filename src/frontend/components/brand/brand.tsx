import Link from "next/link";
import styles from "./brand.module.scss";

/** Site-wide ShareWise wordmark. The symbol is CSS-only and needs no remote assets. */
export function Brand() {
  return (
    <Link className={styles.brand} href="/" aria-label="ShareWise, home">
      <span className={styles.symbol} aria-hidden="true"><span /><span /><span /><span /></span>
      <span>sharewise<span className={styles.period}>.</span></span>
    </Link>
  );
}

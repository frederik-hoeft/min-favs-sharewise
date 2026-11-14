import Link from "next/link";
import styles from "./brand.module.scss";

export function Brand() {
  return (
    <Link href="/" className={styles.brand} aria-label="ShareWise home">
      <svg className={styles.mark} viewBox="0 0 40 40" fill="none" aria-hidden="true">
        <path d="M20 4.5 35.5 20 20 35.5 4.5 20 20 4.5Z" stroke="currentColor" strokeWidth="2.8" />
        <path d="M20 4.5v31M4.5 20h31" stroke="currentColor" strokeWidth="2.5" />
        <circle cx="20" cy="20" r="4.5" fill="var(--sw-accent)" />
      </svg>
      <span>sharewise<span className={styles.period}>.</span></span>
    </Link>
  );
}

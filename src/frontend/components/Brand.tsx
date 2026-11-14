import styles from "./Brand.module.scss";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <a className={styles.brand} href="#top" aria-label="ShareWise, back to top">
      <svg className={styles.mark} viewBox="0 0 44 44" aria-hidden="true" fill="none">
        <rect width="44" height="44" rx="13" fill="currentColor" />
        <path d="M14 15h16M14 29h16M14 15v14M30 15v14" stroke="white" strokeWidth="2" opacity=".42" />
        <circle cx="14" cy="15" r="4.1" fill="white" />
        <circle cx="30" cy="15" r="4.1" fill="white" />
        <circle cx="14" cy="29" r="4.1" fill="white" />
        <circle cx="30" cy="29" r="4.1" fill="#94F2D3" />
      </svg>
      {!compact && <span>share<span className={styles.bold}>wise</span><span className={styles.period}>.</span></span>}
    </a>
  );
}

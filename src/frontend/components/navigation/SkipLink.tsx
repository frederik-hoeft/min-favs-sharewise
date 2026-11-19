import styles from "./SkipLink.module.scss";

/** Skip to the main landmark in either the public or application AppShell. */
export function SkipLink({ href }: { href: `#${string}` }) {
  return <a className={styles.link} href={href}>Skip to content</a>;
}

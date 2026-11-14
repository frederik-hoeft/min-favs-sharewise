import styles from "./member-avatar.module.scss";

type MemberAvatarProps = { initials: string; tone: "sage" | "sand" | "lavender" | "clay"; small?: boolean };

/** Small identity motif shared by future participant and finance UIs. */
export function MemberAvatar({ initials, tone, small = false }: MemberAvatarProps) {
  return <span className={`${styles.avatar} ${styles[tone]} ${small ? styles.small : ""}`} aria-hidden="true">{initials}</span>;
}

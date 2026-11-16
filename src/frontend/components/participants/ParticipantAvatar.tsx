import { Avatar } from "@mantine/core";
import styles from "./ParticipantAvatar.module.scss";

type AvatarTone = "indigo" | "rose" | "violet" | "plum" | "neutral";

const colors: Record<AvatarTone, string> = {
  indigo: "indigoBrand",
  rose: "red",
  violet: "violet",
  plum: "grape",
  neutral: "gray",
};

/** An initials-based participant identity used in group views and activity records. */
export function ParticipantAvatar({
  name,
  initials,
  tone = "neutral",
  size = "1.875rem",
}: {
  name: string;
  initials: string;
  tone?: AvatarTone;
  size?: string;
}) {
  return (
    <Avatar
      aria-label={name}
      title={name}
      color={colors[tone]}
      variant="light"
      size={size}
      radius="xl"
      className={styles.avatar}
      classNames={{ placeholder: styles.placeholder }}
    >
      {initials}
    </Avatar>
  );
}

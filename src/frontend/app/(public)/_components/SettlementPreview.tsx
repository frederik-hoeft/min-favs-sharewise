import { AvatarGroup, Card, Title } from "@mantine/core";
import { ParticipantAvatar } from "@/components/participants/ParticipantAvatar";
import { displayMoney, previewAvatarTones, previewGroup } from "./preview-data";
import styles from "./SettlementPreview.module.scss";

export function SettlementPreview() {
  return (
    <Card component="article" className={styles.panel}>
      <span className={styles.kicker}>OUTSIDE THE APP</span>
      <Title order={3} className={styles.title}>Paid in real life. Recorded here.</Title>
      <p className={styles.description}>No transfers through ShareWise. Just an accurate record of what your group reports.</p>
      <div className={styles.transfer}>
        <AvatarGroup spacing="0.5625rem" className={styles.avatars} aria-label="Settlement participants">
          <ParticipantAvatar name="Noah" initials="NO" tone={previewAvatarTones.Noah} size="2rem" />
          <ParticipantAvatar name="Maya" initials="MY" tone={previewAvatarTones.Maya} size="2rem" />
        </AvatarGroup>
        <div className={styles.transferDescription}>
          <strong>{previewGroup.settlement.sender} paid {previewGroup.settlement.recipient}</strong>
          <span>External settlement · recorded {previewGroup.settlement.date}</span>
        </div>
        <strong className={styles.transferAmount}>{displayMoney(previewGroup.settlement.amountCents)}</strong>
      </div>
    </Card>
  );
}

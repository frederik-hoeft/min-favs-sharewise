import { AvatarGroup, Badge, Paper, Title } from "@mantine/core";
import { ParticipantAvatar } from "@/components/participants/ParticipantAvatar";
import { displayMoney, previewAvatarTones, previewGroup } from "./preview-data";
import styles from "./OverviewPreview.module.scss";

const { members } = previewGroup;

/** Presentational overview only: this is not a finance dashboard or API consumer. */
export function OverviewPreview() {
  return (
    <div className={styles.scene} aria-label="Illustration of a ShareWise group overview">
      <div className={styles.grid} aria-hidden="true" />
      <Paper className={styles.window}>
        <div className={styles.windowBar}>
          <div className={styles.windowBrand}><span className={styles.brandDot} /> sharewise <span className={styles.slash}>/</span> Groups</div>
          <span className={styles.sample}>EXAMPLE VIEW</span>
        </div>
        <div className={styles.inner}>
          <div className={styles.groupHeader}>
            <div>
              <p className={styles.overline}>YOUR GROUP</p>
              <Title order={2}>{previewGroup.name}</Title>
              <p className={styles.minor}>4 participants <span aria-hidden="true">·</span> EUR</p>
            </div>
            <AvatarGroup className={styles.avatars} spacing="0.5625rem" aria-label="Maya, Leo, Ana and Noah">
              {members.map((member) => (
                <ParticipantAvatar key={member.name} name={member.name} initials={member.initials}
                  tone={previewAvatarTones[member.name]} size="var(--sw-group-avatar-size)" />
              ))}
            </AvatarGroup>
          </div>
          <Paper className={styles.summary}>
            <div>
              <p>Shared expenses</p>
              <strong>{displayMoney(previewGroup.sharedCents)}</strong>
              <span>Across 3 expenses</span>
            </div>
            <div className={styles.summaryRight}>
              <span className={styles.summaryGlyph} aria-hidden="true">↗</span>
              <p>External settlements recorded</p>
              <strong>{displayMoney(previewGroup.settlement.amountCents)}</strong>
            </div>
          </Paper>
          <div className={styles.balanceTitle}>
            <Title order={3}>Group balances</Title>
            <Badge color="indigoBrand" variant="light" size="sm">Up to date</Badge>
          </div>
          <div className={styles.balances}>
            {members.map((member) => (
              <div className={styles.balance} key={member.name}>
                <ParticipantAvatar name={member.name} initials={member.initials} />
                <span className={styles.person}>{member.name}</span>
                <span className={member.balanceCents > 0 ? styles.positive : styles.negative}>
                  {member.balanceCents > 0 ? "+" : "−"}{displayMoney(member.balanceCents)}
                </span>
              </div>
            ))}
          </div>
          <p className={styles.legend}>Positive balances are owed to a participant.</p>
        </div>
      </Paper>
      <Paper className={styles.floating} aria-hidden="true">
        <span className={styles.floatingIcon}>↗</span>
        <div><small>RECORDED SETTLEMENT</small><strong>{previewGroup.settlement.sender} → {previewGroup.settlement.recipient}</strong><span>{displayMoney(previewGroup.settlement.amountCents)} · outside ShareWise</span></div>
      </Paper>
    </div>
  );
}

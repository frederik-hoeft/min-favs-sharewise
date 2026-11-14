import { Badge } from "@mantine/core";
import { displayMoney, previewGroup } from "./preview-data";
import styles from "./OverviewPreview.module.scss";

const { members } = previewGroup;

/** Presentational overview only: this is not a finance dashboard or API consumer. */
export function OverviewPreview() {
  return (
    <div className={styles.scene} aria-label="Illustration of a ShareWise group overview">
      <div className={styles.grid} aria-hidden="true" />
      <div className={styles.window}>
        <div className={styles.windowBar}>
          <div className={styles.windowBrand}><span className={styles.brandDot} /> sharewise <span className={styles.slash}>/</span> Groups</div>
          <span className={styles.sample}>EXAMPLE VIEW</span>
        </div>
        <div className={styles.inner}>
          <div className={styles.groupHeader}>
            <div>
              <p className={styles.overline}>YOUR GROUP</p>
              <h2>{previewGroup.name}</h2>
              <p className={styles.minor}>4 participants <span aria-hidden="true">·</span> EUR</p>
            </div>
            <div className={styles.avatars} aria-label="Maya, Leo, Ana and Noah">
              {members.map((member) => <span key={member.name} title={member.name}>{member.initials}</span>)}
            </div>
          </div>
          <div className={styles.summary}>
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
          </div>
          <div className={styles.balanceTitle}>
            <h3>Group balances</h3>
            <Badge color="indigoBrand" variant="light" size="sm">Up to date</Badge>
          </div>
          <div className={styles.balances}>
            {members.map((member) => (
              <div className={styles.balance} key={member.name}>
                <span className={styles.miniAvatar}>{member.initials}</span>
                <span className={styles.person}>{member.name}</span>
                <span className={member.balanceCents > 0 ? styles.positive : styles.negative}>
                  {member.balanceCents > 0 ? "+" : "−"}{displayMoney(member.balanceCents)}
                </span>
              </div>
            ))}
          </div>
          <p className={styles.legend}>Positive balances are owed to a participant.</p>
        </div>
      </div>
      <div className={styles.floating} aria-hidden="true">
        <span className={styles.floatingIcon}>↗</span>
        <div><small>RECORDED SETTLEMENT</small><strong>{previewGroup.settlement.sender} → {previewGroup.settlement.recipient}</strong><span>{displayMoney(previewGroup.settlement.amountCents)} · outside ShareWise</span></div>
      </div>
    </div>
  );
}

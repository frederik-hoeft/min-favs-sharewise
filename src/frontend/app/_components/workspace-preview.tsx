import { Avatar, AvatarGroup, Badge } from "@mantine/core";
import { previewGroup } from "../_data/preview";
import styles from "./workspace-preview.module.scss";

/** Static application concept. No business actions or operational navigation. */
export function WorkspacePreview() {
  return (
    <figure className={styles.frame} aria-label="Illustrative ShareWise group balance dashboard">
      <div className={styles.chrome} aria-hidden="true">
        <span className={styles.windowControls}><i /><i /><i /></span>
        <span>sharewise / groups / cabin-retreat</span>
        <span className={styles.chromeTag}>CONCEPT PREVIEW</span>
      </div>
      <div className={styles.shell}>
        <aside className={styles.sidebar} aria-label="Illustrative workspace sidebar">
          <div className={styles.sidebarIdentity}>
            <span className={styles.miniMark}>s<span>w</span></span>
            <span>Workspace</span>
          </div>
          <div className={styles.sidebarEyebrow}>YOUR SPACE</div>
          <div className={styles.sidebarActive}><span className={styles.navGlyph} /> Overview</div>
          <div className={styles.sidebarItem}><span className={styles.navGlyph} /> Groups</div>
          <div className={styles.sidebarItem}><span className={styles.navGlyph} /> Activity</div>
          <div className={styles.sidebarEyebrow}>YOUR GROUPS</div>
          <div className={styles.groupItem}><span className={styles.groupSymbol}>C</span> Cabin retreat</div>
          <div className={styles.sidebarItem}><span className={styles.navGlyph} /> Weekend plans</div>
          <div className={styles.sidebarBottom}><span className={styles.profileDot}>AV</span> Ava Morgan</div>
        </aside>
        <div className={styles.workspace}>
          <div className={styles.breadcrumbs}>Groups <span>/</span> {previewGroup.name}</div>
          <div className={styles.headingRow}>
            <div>
              <span className={styles.kicker}>GROUP OVERVIEW</span>
              <div className={styles.workspaceTitle}>{previewGroup.name}</div>
              <p>Everything your group shares, in one place.</p>
            </div>
            <AvatarGroup spacing="sm" className={styles.avatars}>
              {previewGroup.participants.map((person) => (
                <Avatar key={person.initials} name={person.name} color={person.avatarColor} variant="filled" size="sm" radius="xl" />
              ))}
            </AvatarGroup>
          </div>
          <div className={styles.metrics}>
            <div className={styles.metricPrimary}>
              <span>Total group expenses</span>
              <strong>{previewGroup.expensesTotal}</strong>
              <small>{previewGroup.expenses.length} recorded expenses</small>
            </div>
            <div className={styles.metricSecondary}>
              <span>Your balance</span>
              <strong>{previewGroup.participants[0].balance}</strong>
              <small>You&apos;re owed</small>
            </div>
          </div>
          <div className={styles.sectionTitle}>
            <div className={styles.sectionLabel}>Group balances</div>
            <span>{previewGroup.currency}</span>
          </div>
          <div className={styles.balances}>
            {previewGroup.participants.map((person) => (
              <div className={styles.balanceRow} key={person.initials}>
                <span className={`${styles.avatarFallback} ${styles[person.color]}`}>{person.initials}</span>
                <span className={styles.personName}>{person.name}</span>
                <span className={`${styles.balanceAmount} ${styles[person.direction]}`}>{person.balance}</span>
              </div>
            ))}
          </div>
          <div className={styles.activityLine}>
            <span className={styles.activityIcon} aria-hidden="true">↗</span>
            <span>External settlement recorded <b>{previewGroup.settlement.sender} → {previewGroup.settlement.recipient}</b></span>
            <Badge size="xs" color="ocean" variant="light">{previewGroup.settlement.amount}</Badge>
          </div>
        </div>
      </div>
      <figcaption className={styles.caption}>Illustrative interface · Not a live account</figcaption>
    </figure>
  );
}

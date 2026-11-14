import { Badge } from "@mantine/core";
import { expenses, members } from "./illustrative-ledger";
import { MemberAvatar } from "@/components/member-avatar";
import styles from "./overview-preview.module.scss";

/** Static application composition, intentionally no data fetching or actionable finance controls. */
export function OverviewPreview() {
  return (
    <div className={styles.frame} aria-label="Illustrative ShareWise group overview">
      <div className={styles.browserTop} aria-hidden="true">
        <div className={styles.dots}><i /><i /><i /></div>
        <span>sharewise / group overview</span>
        <span className={styles.browserFlag}>CONCEPT PREVIEW</span>
      </div>
      <div className={styles.workspace}>
        <aside className={styles.sidebar} aria-label="Illustrative workspace sidebar">
          <div className={styles.sideIdentity}><span className={styles.sideMark}>✳</span> SHAREWISE <span className={styles.sideMini}>/ DEMO</span></div>
          <div className={styles.sideCaption}>YOUR GROUP</div>
          <div className={styles.sideGroup}><span className={styles.groupGlyph}>C</span> Copenhagen <span aria-hidden="true">⌄</span></div>
          <div className={styles.sideCaption}>WORKSPACE</div>
          <div className={`${styles.sideItem} ${styles.sideActive}`}><span>▤</span> Overview</div>
          <div className={styles.sideItem}><span>≡</span> Expenses</div>
          <div className={styles.sideItem}><span>↔</span> Settlements</div>
          <div className={styles.sideItem}><span>◷</span> History</div>
          <div className={styles.sideSpacer} />
          <div className={styles.sideBottom}><span className={styles.sideSmallAvatar}>MC</span><span>Maya Chen<small>Group member</small></span></div>
        </aside>
        <div className={styles.dashboard}>
          <div className={styles.crumb}>Groups <span>/</span> Weekend in Copenhagen <span>/</span> Overview</div>
          <div className={styles.boardHeading}>
            <div>
              <div className={styles.eyebrow}>GROUP OVERVIEW <span>·</span> EUR</div>
              <h3>Weekend in<br />Copenhagen<span className={styles.headingDot}>.</span></h3>
              <p>Everyone, every expense, one clear picture.</p>
            </div>
            <div className={styles.participants}>
              <div className={styles.avatarStack}>{members.map((member) => <MemberAvatar key={member.initials} initials={member.initials} tone={member.tone} />)}</div>
              <span>4 participants</span>
            </div>
          </div>
          <div className={styles.summaryRow}>
            <div className={styles.mainMetric}>
              <span className={styles.metricIcon} aria-hidden="true">↗</span>
              <div className={styles.metricLabel}>Maya&apos;s balance <span>after external settlement</span></div>
              <strong>+€215<span>.00</span></strong>
              <span className={styles.metricNote}>Maya is owed this amount in the group</span>
            </div>
            <div className={styles.secondaryMetric}>
              <span>Total shared expenses</span>
              <strong>€860<span>.00</span></strong>
              <div className={styles.metricFoot}><span>4 expenses</span><span>€215 per person</span></div>
            </div>
          </div>
          <div className={styles.detailsGrid}>
            <div className={styles.surfaceCard}>
              <div className={styles.cardHeading}><div><span className={styles.cardKicker}>AS OF REVISION 12</span><h4>Group balances</h4></div><Badge variant="light" color="pine" size="sm">Net zero</Badge></div>
              <div className={styles.balanceList}>
                {members.map((member, index) => (
                  <div className={styles.balanceRow} key={member.initials}>
                    <MemberAvatar initials={member.initials} tone={member.tone} small />
                    <div className={styles.balanceName}><span>{member.name}</span><div className={styles.track}><i style={{ width: member.bar }} className={index === 0 ? styles.positiveBar : styles.negativeBar} /></div></div>
                    <strong className={index === 0 ? styles.positiveAmount : styles.negativeAmount}>{member.balance}</strong>
                  </div>
                ))}
              </div>
            </div>
            <div className={styles.surfaceCard}>
              <div className={styles.cardHeading}><div><span className={styles.cardKicker}>SHARED SPENDING</span><h4>Expenses</h4></div><span className={styles.tinyMuted}>4 recorded</span></div>
              <div className={styles.expenseList}>
                {expenses.map((expense) => (
                  <div className={styles.expenseRow} key={expense.title}>
                    <span className={styles.expenseIcon} aria-hidden="true">{expense.icon}</span>
                    <span className={styles.expenseName}><strong>{expense.title}</strong><small>{expense.detail}</small></span>
                    <strong className={styles.expenseAmount}>{expense.amount}</strong>
                  </div>
                ))}
              </div>
            </div>
          </div>
          <div className={styles.previewFoot}><span>Illustrative figures · No live data</span><span>Designed for shared clarity</span></div>
        </div>
      </div>
    </div>
  );
}

import { Badge, Paper } from "@mantine/core";
import { ParticipantAvatar } from "@/components/participant-avatar/participant-avatar";
import { preview, euro } from "../_data/preview";
import styles from "./workspace-preview.module.scss";

/** A static, deliberately non-interactive composite for the public landing page. */
export function WorkspacePreview() {
  return (
    <div className={styles.stage}>
      <Paper className={styles.window} radius="lg" shadow="xl">
        <div className={styles.windowBar}>
          <span className={styles.windowDots} aria-hidden="true"><i /><i /><i /></span>
          <span>sharewise / groups / cabin-weekend</span>
          <span className={styles.sampleTag}>EXAMPLE VIEW</span>
        </div>
        <div className={styles.workspace}>
          <aside className={styles.sidebar} aria-label="Illustrative application sidebar">
            <span className={styles.sidebarSymbol} aria-hidden="true">≋</span>
            <span className={styles.sidebarLabel}>WORKSPACE</span>
            <span className={styles.sideItemActive}>◫ <span>Overview</span></span>
            <span className={styles.sideItem}>▤ <span>Expenses</span></span>
            <span className={styles.sideItem}>◷ <span>History</span></span>
            <span className={styles.sidebarBottom}>YOUR GROUPS</span>
            <span className={styles.groupTag}><span className={styles.groupDot} /> {preview.name}</span>
          </aside>
          <div className={styles.content}>
            <div className={styles.breadcrumb}>My groups <span>/</span> {preview.name}</div>
            <div className={styles.headingRow}>
              <div>
                <h2>{preview.name}</h2>
                <p>{preview.location}</p>
              </div>
              <Badge color="forest" variant="light" radius="sm" size="xs">Open group</Badge>
            </div>
            <div className={styles.summaryGrid}>
              <div className={styles.summaryBlock}>
                <span>Total expenses</span>
                <strong>{euro(preview.totalExpenses)}</strong>
                <small>Across {preview.expenses.length} expenses</small>
              </div>
              <div className={styles.summaryBlock}>
                <span>Group members</span>
                <strong>{String(preview.participants.length).padStart(2, "0")}</strong>
                <div className={styles.avatarStack} role="img" aria-label="Maya, Theo, Lina and Noah">
                  {preview.participants.map((person) => (
                    <ParticipantAvatar key={person.name} name={person.name} initials={person.initials} tint={person.tint} size={24} />
                  ))}
                </div>
              </div>
            </div>
            <section className={styles.balancePanel} aria-label="Illustrative group balances">
              <div className={styles.panelHeading}>
                <h3>Group balances</h3>
                <span>EUR</span>
              </div>
              <div className={styles.balanceList}>
                {preview.participants.map((person) => (
                  <div className={styles.balanceRow} key={person.name}>
                    <ParticipantAvatar name={person.name} initials={person.initials} tint={person.tint} size={30} />
                    <span>{person.name}</span>
                    <span className={person.balance > 0 ? styles.positive : styles.negative}>
                      {person.balance > 0 ? "+" : "−"}{euro(Math.abs(person.balance))}
                    </span>
                  </div>
                ))}
              </div>
              <p className={styles.balanceLegend}>Positive means owed money · Negative means owes money</p>
            </section>
            <div className={styles.recentHeading}><h3>Recent expenses</h3><span>{preview.expenses.length} entries</span></div>
            <div className={styles.expenses}>
              {preview.expenses.map((item) => (
                <div className={styles.expenseRow} key={item.name}>
                  <span className={styles.expenseIcon} aria-hidden="true">{item.icon}</span>
                  <span className={styles.expenseName}><strong>{item.name}</strong><small>{item.date} · Paid by {item.paidBy}</small></span>
                  <strong>{euro(item.amount)}</strong>
                </div>
              ))}
            </div>
          </div>
        </div>
      </Paper>
      <Paper className={styles.settlementCard} radius="lg" shadow="lg" aria-label="Example external settlement record">
        <span className={styles.settlementIcon} aria-hidden="true">↗</span>
        <div>
          <span className={styles.settlementEyebrow}>EXTERNAL SETTLEMENT RECORDED</span>
          <strong>Noah paid Maya {euro(preview.settlementTotal)}</strong>
          <span className={styles.settlementNote}>Recorded here, transferred elsewhere.</span>
        </div>
      </Paper>
    </div>
  );
}

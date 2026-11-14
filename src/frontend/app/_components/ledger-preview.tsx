import { Badge } from "@mantine/core";
import { example } from "../_data/example";
import styles from "./ledger-preview.module.scss";

/** Presentational group workspace. No interactive finance controls are depicted. */
export function LedgerPreview() {
  return (
    <section className={styles.window} aria-label="Illustrative ShareWise group workspace">
      <div className={styles.windowBar}>
        <div className={styles.dots} aria-hidden="true"><i /><i /><i /></div>
        <span>SHAREWISE / GROUP OVERVIEW</span>
        <span className={styles.sample}>ILLUSTRATIVE PREVIEW</span>
      </div>
      <div className={styles.workspace}>
        <aside className={styles.sidebar} aria-label="Example workspace areas">
          <div className={styles.sidebarLogo} aria-hidden="true">s.</div>
          <div className={styles.sidebarNav}>
            <span className={styles.active}>◫</span><span>≡</span><span>◷</span><span>⚙</span>
          </div>
          <div className={styles.sidebarAvatar} aria-hidden="true">EV</div>
        </aside>
        <div className={styles.main}>
          <div className={styles.workspaceHeader}>
            <div>
              <p className={styles.eyebrow}>YOUR GROUP / 01</p>
              <h2>{example.groupName}</h2>
              <p className={styles.subtle}>{example.period} <span aria-hidden="true">·</span> 4 participants</p>
            </div>
            <Badge variant="light" color="copper" radius="sm" size="sm">OPEN GROUP</Badge>
          </div>
          <div className={styles.summaryGrid}>
            <div className={styles.primaryMetric}>
              <span className={styles.metricLabel}>Total shared expenses</span>
              <strong>{example.totals.expenses}</strong>
              <span className={styles.metricFoot}>{example.totals.expenseCount} <span aria-hidden="true">↗</span></span>
              <div className={styles.metricDecoration} aria-hidden="true"><span /><span /><span /><span /></div>
            </div>
            <div className={styles.secondaryMetric}>
              <span className={styles.metricLabel}>External settlements recorded</span>
              <strong>{example.totals.settlements}</strong>
              <span className={styles.metricFoot}>{example.totals.settlementCount}</span>
            </div>
          </div>
          <div className={styles.lowerGrid}>
            <div className={styles.balances}>
              <div className={styles.panelHeading}><h3>Group balances</h3><span>{example.currency}</span></div>
              <div className={styles.balanceList}>
                {example.people.map((person, index) => (
                  <div className={styles.balanceRow} key={person.name}>
                    <span className={`${styles.avatar} ${styles[`avatar${index}`]}`}>{person.initials}</span>
                    <span className={styles.member}>{person.name}</span>
                    <span className={person.tone === "positive" ? styles.positive : styles.negative}>{person.balance}</span>
                  </div>
                ))}
              </div>
              <p className={styles.balanceNote}>Positive balances indicate money owed to a participant.</p>
            </div>
            <div className={styles.latest}>
              <div className={styles.panelHeading}><h3>Recent activity</h3><span>REV 06</span></div>
              <div className={styles.activity}>
                <span className={styles.activityIcon} aria-hidden="true">↗</span>
                <div><strong>House rental</strong><small>Corrected · Nov 12</small></div>
                <b>{example.expenses[0].amount}</b>
              </div>
              <div className={styles.activity}>
                <span className={styles.activityIcon} aria-hidden="true">↙</span>
                <div><strong>External settlement</strong><small>Noah → Mina · Nov 11</small></div>
                <b>{example.settlements[1].amount}</b>
              </div>
              <div className={styles.activity}>
                <span className={styles.activityIcon} aria-hidden="true">↙</span>
                <div><strong>External settlement</strong><small>Leo → Mina · Nov 11</small></div>
                <b>{example.settlements[0].amount}</b>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

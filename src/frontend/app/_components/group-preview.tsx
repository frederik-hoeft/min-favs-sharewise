import { Badge } from "@mantine/core";
import { GroupBalanceTable } from "@/components/finance/group-balance-table";
import { formatMoney } from "@/lib/format-money";
import { sampleGroup } from "./preview-data";
import styles from "./group-preview.module.scss";

export function GroupPreview() {
  const revision = String(sampleGroup.currentRevision).padStart(3, "0");

  return (
    <figure className={styles.frame} aria-label="Illustrative group overview for The Coast House">
      <div className={styles.app}>
        <aside className={styles.sidebar} aria-label="Example application navigation, not interactive">
          <div className={styles.sidebarBrand}>sw<span>.</span></div>
          <div className={styles.sideLabel}>Workspace</div>
          <div className={styles.sideRow}>Overview</div>
          <div className={styles.sideRowActive}>Groups <span>02</span></div>
          <div className={styles.sideRow}>Activity</div>
          <div className={styles.sideRule} />
          <div className={styles.sideLabel}>Your groups</div>
          <div className={styles.selectedGroup}><span className={styles.selectedDot} /> The Coast House</div>
          <div className={styles.sideRow}>Studio Four</div>
          <div className={styles.sidebarBottom}>SHAREWISE / DEMO</div>
        </aside>
        <div className={styles.content}>
          <div className={styles.topline}>
            <span>GROUPS <span className={styles.slash}>/</span> THE COAST HOUSE</span>
            <Badge color="gray" variant="outline" size="sm">Illustrative preview</Badge>
          </div>
          <div className={styles.headingRow}>
            <div>
              <div className={styles.overline}>Group overview / EUR</div>
              <h3>{sampleGroup.name}<span className={styles.titleMark}>.</span></h3>
              <p>Four people. One shared record.</p>
            </div>
            <span className={styles.openStatus}><span aria-hidden="true" /> Open group</span>
          </div>
          <div className={styles.metrics}>
            <div><span>Total expenses</span><strong>{formatMoney(sampleGroup.totalExpenses, sampleGroup.currency)}</strong><small>3 recorded expenses</small></div>
            <div><span>External settlements</span><strong>{formatMoney(sampleGroup.settlement.amount, sampleGroup.currency)}</strong><small>1 recorded transfer</small></div>
            <div><span>Group revision</span><strong>{revision}<span className={styles.revisionSign}> / current</span></strong><small>Full history retained</small></div>
          </div>
          <div className={styles.panels}>
            <section className={styles.balancePanel} aria-labelledby="balances-heading">
              <div className={styles.panelHead}>
                <h4 id="balances-heading">Member balances</h4>
                <span>As of revision {revision}</span>
              </div>
              <GroupBalanceTable participants={sampleGroup.participants} currency={sampleGroup.currency} />
              <p className={styles.note}>Positive balances are owed to a member. All balances sum to zero.</p>
            </section>
            <section className={styles.activityPanel} aria-labelledby="activity-heading">
              <div className={styles.panelHead}><h4 id="activity-heading">Latest activity</h4></div>
              <div className={styles.activityRow}><span className={styles.activityIndex}>05</span><div><strong>Settlement recorded</strong><small>Mina → Clara · externally</small></div><span className={styles.activityAmount}>{formatMoney(10000, sampleGroup.currency)}</span></div>
              <div className={styles.activityRow}><span className={styles.activityIndex}>04</span><div><strong>Groceries revised</strong><small>€220.00 → €240.00</small></div><span className={styles.activityAmount}>v2</span></div>
              <div className={styles.activityRow}><span className={styles.activityIndex}>03</span><div><strong>Car rental</strong><small>Paid by Noah</small></div><span className={styles.activityAmount}>{formatMoney(32000, sampleGroup.currency)}</span></div>
              <div className={styles.historyHint}>An unbroken record, not a rolling spreadsheet. <span aria-hidden="true">↗</span></div>
            </section>
          </div>
        </div>
      </div>
      <figcaption className={styles.caption}><span>FIG. 01</span> A sample group overview, with illustrative amounts and no live data.</figcaption>
    </figure>
  );
}

import { Badge } from "@mantine/core";
import { previewGroup } from "./financeFixtures";
import styles from "./FinanceWorkspacePreview.module.scss";

export function FinanceWorkspacePreview() {
  return (
    <figure className={styles.preview} aria-label="Illustrative group finance overview, not a functioning application">
      <div className={styles.window}>
        <div className={styles.chrome}>
          <div className={styles.windowDots} aria-hidden="true"><span /><span /><span /></div>
          <span className={styles.location}>sharewise / workspace / overview</span>
          <span className={styles.previewTag}>DESIGN PREVIEW</span>
        </div>
        <div className={styles.app}>
          <aside className={styles.side} aria-label="Illustrative application navigation">
            <span className={styles.sideBrand}><span className={styles.sideBrandMark}>✣</span> sharewise.</span>
            <span className={styles.sideCaption}>WORKSPACE</span>
            <span className={`${styles.sideItem} ${styles.sideActive}`}><span>◫</span> Overview</span>
            <span className={styles.sideItem}><span>▤</span> Expenses</span>
            <span className={styles.sideItem}><span>⇄</span> Settlements</span>
            <span className={styles.sideItem}><span>◷</span> History</span>
            <div className={styles.sideFoot}>SIMULATED DATA<br /><span>NO ACCOUNT REQUIRED</span></div>
          </aside>
          <div className={styles.content}>
            <div className={styles.workspaceHeading}>
              <div>
                <span className={styles.breadcrumb}>MY GROUPS <span>/</span> LISBON RETREAT</span>
                <h2>{previewGroup.name}<span className={styles.dot}>.</span></h2>
                <p>{previewGroup.subtitle}</p>
              </div>
              <div className={styles.avatars} aria-label="Maya, Noah, Lena and Ezra">
                {previewGroup.participants.map((person, index) => (
                  <span className={styles.avatar} data-index={index} key={person.name} title={person.name}>{person.initials}</span>
                ))}
              </div>
            </div>
            <div className={styles.metrics}>
              <div className={styles.metricPrimary}>
                <span className={styles.metricLabel}>TOTAL GROUP EXPENSES <span aria-hidden="true">↗</span></span>
                <strong>{previewGroup.expensesTotal}</strong>
                <span className={styles.metricDescription}>Across {previewGroup.expenseCount} recorded expenses</span>
                <span className={styles.metricDecoration} aria-hidden="true" />
              </div>
              <div className={styles.metricSecondary}>
                <span className={styles.metricLabel}>EXTERNAL SETTLEMENTS</span>
                <strong>{previewGroup.externallySettled}</strong>
                <span className={styles.metricDescription}>Recorded · paid outside ShareWise</span>
                <svg className={styles.spark} viewBox="0 0 140 32" fill="none" aria-hidden="true">
                  <path d="M0 27H140" stroke="currentColor" opacity=".14" />
                  <path d="m0 25 17-7 17 4 18-13 20 7 19-11 22 9 27-12" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            <div className={styles.panels}>
              <section className={styles.balancePanel} aria-label="Illustrative current balances">
                <div className={styles.panelHeading}>
                  <div><span className={styles.panelOverline}>AT A GLANCE</span><h3>Group balances</h3></div>
                  <Badge size="xs" color="mint">Balanced</Badge>
                </div>
                <div className={styles.balanceList}>
                  {previewGroup.participants.map((person, i) => (
                    <div className={styles.balanceRow} key={person.name}>
                      <span className={styles.balancePerson}><span className={styles.tinyAvatar} data-index={i}>{person.initials}</span>{person.name}</span>
                      <span className={styles.balanceTrack} aria-hidden="true"><span className={person.amount >= 0 ? styles.barPositive : styles.barNegative} style={{ width: `${Math.abs(person.amount) / 120 * 100}%` }} /></span>
                      <strong className={person.amount >= 0 ? styles.positive : styles.negative}>{person.balance}</strong>
                    </div>
                  ))}
                </div>
                <p className={styles.netNote}><span aria-hidden="true">◎</span> Group balances net to €0.00</p>
              </section>
              <section className={styles.activityPanel} aria-label="Illustrative financial activity">
                <div className={styles.panelHeading}>
                  <div><span className={styles.panelOverline}>TRACEABLE BY DESIGN</span><h3>Recent activity</h3></div>
                </div>
                <div className={styles.activityList}>
                  {previewGroup.activity.map((event, index) => (
                    <div className={styles.activityRow} key={`${event.type}-${index}`}>
                      <span className={styles.activityIcon} aria-hidden="true">{event.type === "revision" ? "↺" : "↗"}</span>
                      <div><strong>{event.title}</strong><span>{event.detail}</span></div>
                      <b>{event.amount}</b>
                    </div>
                  ))}
                </div>
              </section>
            </div>
            <div className={styles.previewBottom}><span className={styles.statusLight} /> CURRENT POSITION <span>·</span> HISTORY PRESERVED <span className={styles.previewPage}>01 / 04</span></div>
          </div>
        </div>
      </div>
      <figcaption className={styles.caption}>Concept preview with fictional people and amounts. No financial action is available on this page.</figcaption>
    </figure>
  );
}

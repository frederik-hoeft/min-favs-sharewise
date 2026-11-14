import { exampleGroup } from "./illustration-data";
import styles from "./dashboard-preview.module.scss";

/** One cohesive mock workspace, intentionally without active business controls. */
export function DashboardPreview() {
  return (
    <figure className={styles.figure} aria-label="Illustrative ShareWise group overview">
      <div className={styles.window}>
        <aside className={styles.sidebar} aria-label="Illustrative application sections">
          <div className={styles.miniBrand}><span className={styles.miniMark}>✳</span> sharewise<span className={styles.brandDot}>.</span></div>
          <span className={styles.sideEyebrow}>WORKSPACE</span>
          <div className={styles.sideCurrent}><span className={styles.sideIcon}>⌂</span> Overview</div>
          <div className={styles.sideEntry}><span className={styles.sideIcon}>▤</span> Expenses</div>
          <div className={styles.sideEntry}><span className={styles.sideIcon}>⇄</span> Settlements</div>
          <div className={styles.sideEntry}><span className={styles.sideIcon}>◷</span> History</div>
          <div className={styles.sideBottom}><span className={styles.sideBadge}>CW</span><span>Cabin weekend<small>4 people</small></span></div>
        </aside>
        <div className={styles.content}>
          <div className={styles.topline}><span>YOUR GROUPS <span className={styles.chevron}>/</span> CABIN WEEKEND</span><span className={styles.previewBadge}>Preview</span></div>
          <div className={styles.groupHeader}>
            <div><h2>{exampleGroup.name}</h2><p>Everyone on the same page.</p></div>
            <div className={styles.avatarStack} aria-label="Ava, Ben, Cleo and Dani">
              {exampleGroup.participants.map((person) => <span key={person.name} className={`${styles.avatar} ${styles[person.tone]}`} title={person.name}>{person.initials}</span>)}
            </div>
          </div>
          <div className={styles.overviewGrid}>
            <section className={styles.spentCard} aria-label="Total expenses">
              <span className={styles.cardLabel}>TOTAL GROUP EXPENSES</span>
              <strong>€400<span>.00</span></strong>
              <div className={styles.spentGraph} role="img" aria-label="Cabin 60 percent, groceries 30 percent, snacks 10 percent">
                <span /><span /><span />
              </div>
              <div className={styles.spentBottom}><span>3 expenses</span><span>EUR <span aria-hidden="true">↗</span></span></div>
            </section>
            <section className={styles.balanceCard} aria-label="Current balances after recorded settlement">
              <div className={styles.cardHeading}><h3>Group balances</h3><span>EUR</span></div>
              {exampleGroup.participants.map((person) => (
                <div className={styles.balanceRow} key={person.name}>
                  <span className={`${styles.smallAvatar} ${styles[person.tone]}`}>{person.initials}</span>
                  <span>{person.name}</span>
                  <strong className={person.balance.startsWith("+") ? styles.positive : styles.negative}>{person.balance}</strong>
                </div>
              ))}
            </section>
          </div>
          <section className={styles.expensesCard} aria-label="Recently recorded expenses">
            <div className={styles.cardHeading}><h3>Recent expenses</h3><span>3 records</span></div>
            {exampleGroup.expenses.map((expense) => (
              <div className={styles.expenseRow} key={expense.title}>
                <span className={styles.expenseIcon} aria-hidden="true">{expense.symbol}</span>
                <span className={styles.expenseName}><strong>{expense.title}</strong><small>{expense.detail}</small></span>
                <strong className={styles.expenseAmount}>{expense.amount}</strong>
              </div>
            ))}
          </section>
          <div className={styles.settlementNote}>
            <span className={styles.settlementIcon} aria-hidden="true">↗</span>
            <span><strong>External settlement recorded</strong><small>Dani sent €40 to Ben outside ShareWise</small></span>
            <span className={styles.settlementAmount}>€40</span>
          </div>
        </div>
      </div>
      <figcaption>Concept preview · illustrative entries, not live financial data</figcaption>
    </figure>
  );
}

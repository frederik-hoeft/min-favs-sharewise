import { Avatar, AvatarGroup, Badge } from "@mantine/core";
import { BalanceList } from "@/components/finance/balance-list";
import { currentBalances, expenses, participants } from "../_data/illustrative-ledger";
import styles from "./overview-preview.module.scss";

/** A read-only illustration of an application group overview, not a functional screen. */
export function OverviewPreview() {
  return (
    <div className={styles.window} aria-label="Illustrative ShareWise group overview">
      <div className={styles.chrome} aria-hidden="true">
        <span className={styles.chromeDots}><i /><i /><i /></span>
        <span>sharewise / groups / sunday table</span>
        <span className={styles.chromeGlyph}>✳</span>
      </div>
      <div className={styles.content}>
        <div className={styles.headline}>
          <div>
            <p className={styles.eyebrow}>GROUP OVERVIEW <span>·</span> EUR</p>
            <h2>Sunday Table <span className={styles.sun}>✳</span></h2>
            <p className={styles.subheading}>Good food, better company.</p>
          </div>
          <AvatarGroup className={styles.avatarGroup} spacing="sm">
            {participants.map((person) => (
              <Avatar key={person.id} size="md" radius="xl" variant="filled" color={person.color}>
                {person.initials}
              </Avatar>
            ))}
          </AvatarGroup>
        </div>
        <div className={styles.stats}>
          <div className={styles.statMain}>
            <span className={styles.label}>Total shared expenses</span>
            <strong>€180<span>.00</span></strong>
            <span className={styles.note}><span className={styles.noteDot} /> 3 expenses in this group</span>
          </div>
          <div className={styles.statSide}>
            <span className={styles.label}>Remaining balances</span>
            <strong>€10<span>.00</span></strong>
            <span className={styles.note}>Total owed by Lina</span>
          </div>
        </div>
        <div className={styles.twoColumns}>
          <div className={styles.ledger}>
            <div className={styles.panelHeader}><h3>Recent expenses</h3><Badge color="pine">Equal splits</Badge></div>
            <ul className={styles.expenses}>
              {expenses.map((expense) => (
                <li key={expense.id}>
                  <span className={styles.expenseIcon} aria-hidden="true">{expense.symbol}</span>
                  <span className={styles.expenseText}><strong>{expense.label}</strong><small>{expense.payer} paid</small></span>
                  <span className={styles.expenseAmount}>{expense.amount}</span>
                </li>
              ))}
            </ul>
          </div>
          <div className={styles.balances}>
            <div className={styles.panelHeader}><h3>Net balances</h3></div>
            <BalanceList label="Current group financial positions" balances={currentBalances} participants={participants} />
          </div>
        </div>
        <div className={styles.settlement}><span className={styles.check} aria-hidden="true">↗</span><span><strong>External settlement recorded</strong><small>Lina to Maya · €20.00</small></span><span className={styles.settlementDate}>14 JUL</span></div>
      </div>
      <div className={styles.footerBar}><span>Illustrative product preview</span><span>Group revision 05 <span aria-hidden="true">↗</span></span></div>
    </div>
  );
}

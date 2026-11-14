import { Avatar, AvatarGroup, Badge } from "@mantine/core";
import { BalanceValue } from "@/components/balance-value/BalanceValue";
import { Brand } from "@/components/brand/Brand";
import { example } from "./preview-data";
import styles from "./OverviewPreview.module.scss";

/** A noninteractive group overview, rendered as part of the static page. */
export function OverviewPreview() {
  return (
    <figure className={styles.figure} aria-label="Example group overview with balances and expenses">
      <div className={styles.window}>
        <div className={styles.toolbar}>
          <Brand compact />
          <span className={styles.toolbarDivider} aria-hidden="true" />
          <span>Group overview</span>
          <span className={styles.toolbarContext}>Example workspace</span>
        </div>

        <div className={styles.content}>
          <div className={styles.headingRow}>
            <div>
              <p className={styles.overline}>YOUR GROUP</p>
              <p className={styles.groupName}>{example.group}</p>
              <p className={styles.groupSubtitle}>Everything shared, in one clear view.</p>
            </div>
            <div className={styles.members} aria-label="Alex, Morgan and Sam are group participants">
              <AvatarGroup spacing="sm">
                {example.balances.map((member) => (
                  <Avatar key={member.name} size="sm" color="brand" variant="light" radius="xl">
                    {member.initials}
                  </Avatar>
                ))}
              </AvatarGroup>
              <span>{example.participantCount} people</span>
            </div>
          </div>

          <div className={styles.summaryGrid}>
            <div className={styles.summaryPrimary}>
              <span className={styles.summaryLabel}>Shared expenses</span>
              <strong>{example.expensesTotal}</strong>
              <span className={styles.summaryDescription}>Across {example.expenses.length} expenses</span>
            </div>
            <div className={styles.summarySecondary}>
              <span className={styles.summaryLabel}>External settlements recorded</span>
              <strong>{example.externalSettlementsTotal}</strong>
              <span className={styles.summaryDescription}>Recorded, not processed</span>
            </div>
          </div>

          <div className={styles.sectionHeading}>
            <p className={styles.sectionTitle}>Where everyone stands</p>
            <Badge color="gray">Current</Badge>
          </div>
          <div className={styles.balanceList}>
            {example.balances.map((member) => (
              <div className={styles.balanceRow} key={member.name}>
                <span className={styles.avatarLetter} aria-hidden="true">{member.initials.slice(0, 1)}</span>
                <span className={styles.memberName}>{member.name}</span>
                <span className={styles.balanceLabel}>{member.label}</span>
                <strong><BalanceValue value={member.value} tone={member.tone} /></strong>
              </div>
            ))}
          </div>

          <div className={styles.sectionHeading}>
            <p className={styles.sectionTitle}>Recorded expenses</p>
            <span className={styles.miniNote}>All amounts in EUR</span>
          </div>
          <div className={styles.expenseList}>
            {example.expenses.map((expense) => (
              <div className={styles.expenseRow} key={expense.name}>
                <span className={styles.expenseIcon} aria-hidden="true">↗</span>
                <span className={styles.expenseName}>
                  <strong>{expense.name}</strong>
                  <small>Paid by {expense.payer}</small>
                </span>
                <strong>{expense.value}</strong>
              </div>
            ))}
          </div>
        </div>
      </div>
      <figcaption>Illustrative interface · Sample group and financial records</figcaption>
    </figure>
  );
}

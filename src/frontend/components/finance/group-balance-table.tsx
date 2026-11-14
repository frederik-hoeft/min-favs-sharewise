import { Table, TableThead, TableTr, TableTh, TableTbody, TableTd } from "@mantine/core";
import { formatMoney } from "@/lib/format-money";
import styles from "./group-balance-table.module.scss";

export type BalanceParticipant = { name: string; initials: string; balance: number };

export function GroupBalanceTable({ participants, currency }: { participants: readonly BalanceParticipant[]; currency: string }) {
  return (
    <Table className={styles.table} withRowBorders={false} aria-label="Illustrative group balances">
      <TableThead>
        <TableTr>
          <TableTh>Participant</TableTh>
          <TableTh ta="right">Net balance</TableTh>
        </TableTr>
      </TableThead>
      <TableTbody>
        {participants.map((person) => (
          <TableTr key={person.name}>
            <TableTd>
              <span className={styles.person}>
                <span className={styles.initials} aria-hidden="true">{person.initials}</span>
                {person.name}
              </span>
            </TableTd>
            <TableTd ta="right" className={person.balance > 0 ? styles.credit : styles.debit}>
              <span className={styles.amount}>{person.balance > 0 ? "+" : "−"}{formatMoney(Math.abs(person.balance), currency)}</span>
            </TableTd>
          </TableTr>
        ))}
      </TableTbody>
    </Table>
  );
}

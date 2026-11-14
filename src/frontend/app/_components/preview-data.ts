/**
 * Purely illustrative ledger for the design candidate. No production finance
 * contracts or calculations are derived from these fixtures.
 *
 * Reconciliation, three equal-split expenses:
 *   Groceries €96 (Alex pays):     Alex +64, Morgan -32, Sam -32
 *   Cabin supplies €54 (Morgan):  Alex -18, Morgan +36, Sam -18
 *   Dinner €90 (Sam):             Alex -30, Morgan -30, Sam +60
 *   External settlement €10, Morgan -> Sam: Morgan +10, Sam -10
 *   Current: Alex +16, Morgan -16, Sam 0. Sum: €0.
 *   Before cabin correction (€48) and settlement (revision 03):
 *   Alex +18, Morgan -30, Sam +12. Sum: €0.
 */
export const example = {
  group: "Lake House Weekend",
  participantCount: 3,
  expensesTotal: "€240.00",
  externalSettlementsTotal: "€10.00",
  balances: [
    { name: "Alex", initials: "AL", value: "+€16.00", label: "Is owed", tone: "positive" },
    { name: "Morgan", initials: "MO", value: "−€16.00", label: "Owes", tone: "negative" },
    { name: "Sam", initials: "SA", value: "€0.00", label: "Even", tone: "neutral" },
  ],
  expenses: [
    { name: "Groceries", payer: "Alex", value: "€96.00" },
    { name: "Cabin supplies", payer: "Morgan", value: "€54.00" },
    { name: "Dinner", payer: "Sam", value: "€90.00" },
  ],
  cabinAllocation: [
    { name: "Alex", initials: "AL", value: "€18.00" },
    { name: "Morgan", initials: "MO", value: "€18.00" },
    { name: "Sam", initials: "SA", value: "€18.00" },
  ],
  historicalBalances: [
    { name: "Alex", value: "+€18.00", tone: "positive" },
    { name: "Morgan", value: "−€30.00", tone: "negative" },
    { name: "Sam", value: "+€12.00", tone: "positive" },
  ],
} as const;

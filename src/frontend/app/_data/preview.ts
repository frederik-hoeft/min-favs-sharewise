/**
 * Intentional illustrative fixtures, not API types or financial calculations.
 * €480 cabin rental split into four €120 shares, €120 groceries into four €30
 * shares; Clara records €60 sent to Ava outside ShareWise. Resulting positions:
 * Ava +270, Ben -30, Clara -90, Daniel -150. All positions sum to zero.
 */
export const previewGroup = {
  name: "Cabin retreat",
  currency: "EUR",
  expensesTotal: "€600.00",
  participants: [
    { name: "Ava", initials: "AV", balance: "+€270.00", direction: "credit", color: "blue", avatarColor: "blue.9" },
    { name: "Ben", initials: "BE", balance: "−€30.00", direction: "debit", color: "orange", avatarColor: "red.9" },
    { name: "Clara", initials: "CL", balance: "−€90.00", direction: "debit", color: "grape", avatarColor: "grape.9" },
    { name: "Daniel", initials: "DA", balance: "−€150.00", direction: "debit", color: "teal", avatarColor: "teal.9" },
  ],
  expenses: [
    { name: "Cabin rental", payer: "Ava", amount: "€480.00", share: "€120.00" },
    { name: "Groceries", payer: "Ben", amount: "€120.00", share: "€30.00" },
  ],
  settlement: {
    sender: "Clara",
    recipient: "Ava",
    amount: "€60.00",
    note: "Transfer made outside ShareWise",
  },
  history: [
    { revision: "12", date: "12 Nov", actor: "Ava", description: "Revised cabin rental", detail: "€440.00 → €480.00", kind: "revision" },
    { revision: "11", date: "11 Nov", actor: "Clara", description: "Recorded an external settlement", detail: "€60.00 to Ava", kind: "settlement" },
    { revision: "10", date: "10 Nov", actor: "Ben", description: "Added groceries", detail: "€120.00 · equal split", kind: "expense" },
    { revision: "9", date: "9 Nov", actor: "Ava", description: "Added cabin rental", detail: "€440.00 · equal split", kind: "expense" },
  ],
} as const;

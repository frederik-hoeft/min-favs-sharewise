/** Editorial sample records only: not an API contract or a financial calculation engine. */
export const sampleGroup = {
  name: "The Coast House",
  currency: "EUR",
  totalExpenses: 140000,
  participants: [
    { name: "Clara", initials: "CL", balance: 39000 },
    { name: "Noah", initials: "NO", balance: 21000 },
    { name: "Mina", initials: "MI", balance: -25000 },
    { name: "Leo", initials: "LE", balance: -35000 },
  ],
  expenses: [
    { name: "Cabin rental", amount: 84000, paidBy: "Clara" },
    { name: "Car rental", amount: 32000, paidBy: "Noah" },
    { name: "Groceries", amount: 24000, paidBy: "Noah" },
  ],
  settlement: { sender: "Mina", recipient: "Clara", amount: 10000 },
  currentRevision: 5,
} as const;

// Amounts are intentionally fixed display data. The balances reflect equal splits
// of the three latest expense amounts and the single external settlement above.

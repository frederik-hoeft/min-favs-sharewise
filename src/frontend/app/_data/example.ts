/**
 * Static editorial fixture, not a financial calculation engine or production domain contract.
 * All financial figures shown in the landing previews originate here.
 */
export const example = {
  groupName: "The Lake House",
  period: "November 2026",
  currency: "EUR",
  totals: {
    expenses: "€500.00",
    settlements: "€80.00",
    expenseCount: "3 expenses",
    settlementCount: "2 recorded transfers",
  },
  people: [
    { initials: "EV", name: "Eva", balance: "−€29.00", tone: "negative" },
    { initials: "NO", name: "Noah", balance: "−€21.00", tone: "negative" },
    { initials: "MI", name: "Mina", balance: "+€115.00", tone: "positive" },
    { initials: "LE", name: "Leo", balance: "−€65.00", tone: "negative" },
  ],
  expenses: [
    { name: "House rental", payer: "Mina", split: "Equal", amount: "€320.00", revision: "02" },
    { name: "Groceries", payer: "Eva", split: "Equal", amount: "€96.00", revision: "01" },
    { name: "Train tickets", payer: "Noah", split: "Equal", amount: "€84.00", revision: "01" },
  ],
  settlements: [
    { sender: "Leo", receiver: "Mina", amount: "€60.00", date: "Nov 11" },
    { sender: "Noah", receiver: "Mina", amount: "€20.00", date: "Nov 11" },
  ],
  allocation: [
    { name: "Eva", share: "€80.00" },
    { name: "Noah", share: "€80.00" },
    { name: "Mina", share: "€80.00" },
    { name: "Leo", share: "€80.00" },
  ],
  history: [
    { revision: "06", action: "House rental corrected", detail: "€300.00 → €320.00 · Mina", date: "Nov 12" },
    { revision: "05", action: "External settlement recorded", detail: "Noah → Mina · €20.00", date: "Nov 11" },
    { revision: "04", action: "External settlement recorded", detail: "Leo → Mina · €60.00", date: "Nov 11" },
    { revision: "03", action: "House rental added", detail: "Paid by Mina · €300.00", date: "Nov 10" },
  ],
} as const;

// Current expenses: 320 + 96 + 84 = 500, four equal shares of 125.
// Before settlements: Mina +195, Eva -29, Noah -41, Leo -125.
// Two external payments (Leo -> Mina 60, Noah -> Mina 20) yield +115/-29/-21/-65.

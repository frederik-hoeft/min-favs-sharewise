/** Deliberately static, human-authored illustrative records, not application DTOs. */
export const previewGroup = {
  name: "Lisbon retreat",
  subtitle: "Product team · 4 participants",
  currency: "EUR",
  expensesTotal: "€840.00",
  externallySettled: "€150.00",
  expenseCount: 4,
  participants: [
    { name: "Maya", initials: "MA", balance: "+€120.00", amount: 120, tone: "positive" },
    { name: "Noah", initials: "NO", balance: "−€50.00", amount: -50, tone: "negative" },
    { name: "Lena", initials: "LE", balance: "−€50.00", amount: -50, tone: "negative" },
    { name: "Ezra", initials: "EZ", balance: "−€20.00", amount: -20, tone: "negative" },
  ],
  expenses: [
    { name: "Accommodation", paidBy: "Maya", amount: "€480.00" },
    { name: "Dinner", paidBy: "Noah", amount: "€160.00" },
    { name: "Groceries", paidBy: "Lena", amount: "€80.00" },
    { name: "Transport", paidBy: "Ezra", amount: "€120.00" },
  ],
  activity: [
    { title: "Accommodation revised", detail: "Maya · Revision 02", amount: "€480.00", type: "revision" },
    { title: "External settlement recorded", detail: "Lena → Maya", amount: "€80.00", type: "settlement" },
    { title: "External settlement recorded", detail: "Ezra → Maya", amount: "€70.00", type: "settlement" },
  ],
} as const;

/**
 * Fictional, internally balanced "Cabin weekend" ledger.
 * Three expenses: 240 + 120 + 40 = 400 EUR, allocated equally (100 EUR/person).
 * A later external transfer of 40 EUR from Dani to Ben yields the displayed
 * current positions: Ava +20, Ben +100, Cleo -60, Dani -60 = 0.
 *
 * This is literal presentation data, not a financial calculation or API model.
 */
export const exampleGroup = {
  name: "Cabin weekend",
  participants: [
    { name: "Ava", initials: "AV", balance: "+€20", tone: "peach" },
    { name: "Ben", initials: "BE", balance: "+€100", tone: "blue" },
    { name: "Cleo", initials: "CL", balance: "−€60", tone: "gold" },
    { name: "Dani", initials: "DA", balance: "−€60", tone: "lilac" },
  ],
  expenses: [
    { title: "Cabin rental", detail: "Paid by Ben · Equal split", amount: "€240.00", symbol: "⌂" },
    { title: "Groceries", detail: "Paid by Ava · Equal split", amount: "€120.00", symbol: "◒" },
    { title: "Road snacks", detail: "Paid by Cleo · Equal split", amount: "€40.00", symbol: "✳" },
  ],
} as const;

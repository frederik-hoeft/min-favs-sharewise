/**
 * Fictional Copenhagen group, in EUR. All amounts are deliberately static.
 * Four equal €215 shares of €860 spending: Maya paid €480, Jonas €88,
 * Leila €164, Sam €128. An external €50 settlement Jonas → Maya yields
 * the displayed balances (+215, -77, -51, -87), summing to zero.
 * This is editorial content, NOT finance application code or an API model.
 */
export const members = [
  { name: "Maya Chen", initials: "MC", tone: "sage", balance: "+€215", bar: "100%" },
  { name: "Jonas Weber", initials: "JW", tone: "sand", balance: "−€77", bar: "36%" },
  { name: "Leila Ortiz", initials: "LO", tone: "lavender", balance: "−€51", bar: "24%" },
  { name: "Sam Brooks", initials: "SB", tone: "clay", balance: "−€87", bar: "40%" },
] as const;

export const expenses = [
  { title: "Apartment", detail: "Paid by Maya · Equal split", amount: "€480.00", icon: "⌂" },
  { title: "Dinner", detail: "Paid by Leila · Equal split", amount: "€164.00", icon: "✳" },
  { title: "Train tickets", detail: "Paid by Sam · Equal split", amount: "€128.00", icon: "↗" },
  { title: "Groceries", detail: "Paid by Jonas · Equal split", amount: "€88.00", icon: "◒" },
] as const;

export const changes = [
  { revision: "12", title: "Apartment expense revised", detail: "Updated from €460 to €480", kind: "Expense revision" },
  { revision: "11", title: "External settlement recorded", detail: "Jonas → Maya · €50", kind: "Settlement" },
  { revision: "10", title: "Dinner expense added", detail: "Paid by Leila · €164", kind: "New expense" },
] as const;

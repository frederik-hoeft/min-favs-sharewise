/**
 * One fixed fictional group, not a data model or business calculation engine.
 * Expenses: 480 + 96 + 84 = 660 EUR, four equal shares of 165 EUR.
 * Noah recorded sending 60 EUR to Maya outside the application.
 * Consequently: Maya +255, Theo -69, Lina -81, Noah -105 (sum 0).
 */
export const preview = {
  name: "Cabin weekend",
  location: "A few days away, made simple",
  currency: "EUR",
  totalExpenses: 660,
  settlementTotal: 60,
  allocationExample: { label: "Equal split", title: "Cabin booking", amount: 480, members: 4, share: 120 },
  participants: [
    { name: "Maya", initials: "MA", tint: "green", balance: 255 },
    { name: "Theo", initials: "TH", tint: "peach", balance: -69 },
    { name: "Lina", initials: "LI", tint: "lilac", balance: -81 },
    { name: "Noah", initials: "NO", tint: "blue", balance: -105 },
  ],
  expenses: [
    { name: "Cabin booking", paidBy: "Maya", date: "May 14", amount: 480, category: "Stay", icon: "⌂" },
    { name: "Groceries", paidBy: "Theo", date: "May 15", amount: 96, category: "Food", icon: "◒" },
    { name: "Dinner", paidBy: "Lina", date: "May 16", amount: 84, category: "Food", icon: "✳" },
  ],
  historicalSnapshot: { revision: "03", maya: 317, noah: -163 },
  events: [
    { revision: "05", label: "External settlement recorded", detail: "Noah → Maya · €60", day: "May 17", kind: "settlement" },
    { revision: "04", label: "Dinner expense revised", detail: "€76 → €84 · by Lina", day: "May 16", kind: "revision" },
    { revision: "03", label: "Dinner expense created", detail: "€76 · paid by Lina", day: "May 16", kind: "expense" },
  ],
} as const;

export function euro(amount: number): string {
  return new Intl.NumberFormat("en-IE", {
    style: "currency",
    currency: "EUR",
    maximumFractionDigits: 2,
    minimumFractionDigits: 0,
  }).format(amount);
}

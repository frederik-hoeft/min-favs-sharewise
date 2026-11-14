/** One coherent fictional ledger shared by the public product illustrations. Amounts in cents. */
export const previewGroup = {
  name: "Lisbon weekender",
  currency: "EUR",
  members: [
    { name: "Maya", initials: "MY", balanceCents: 25000 },
    { name: "Leo", initials: "LE", balanceCents: -5000 },
    { name: "Ana", initials: "AN", balanceCents: -9000 },
    { name: "Noah", initials: "NO", balanceCents: -11000 },
  ],
  expenses: [
    { title: "Apartment", paidBy: "Maya", amountCents: 48000, date: "Nov 6" },
    { title: "Dinner", paidBy: "Leo", amountCents: 12000, date: "Nov 7" },
    { title: "Museum tickets", paidBy: "Ana", amountCents: 8000, date: "Nov 9" },
  ],
  settlement: { sender: "Noah", recipient: "Maya", amountCents: 6000, date: "Nov 8" },
  sharedCents: 68000,
} as const;

export function displayMoney(cents: number): string {
  const absolute = Math.abs(cents);
  return new Intl.NumberFormat("en-IE", { style: "currency", currency: "EUR", minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(absolute / 100);
}

/** Entirely fictional, precomputed illustration; never imported by an API or domain service. */
import type { BalanceDisplay, ParticipantDisplay } from "@/components/finance/balance-list";

export const participants: readonly ParticipantDisplay[] = [
  { id: "maya", name: "Maya", initials: "MY", color: "pine" },
  { id: "noah", name: "Noah", initials: "NO", color: "clay" },
  { id: "lina", name: "Lina", initials: "LI", color: "grape" },
];

// Revision 1: €84 groceries, paid by Maya; three equal €28 shares.
// Revision 2: €60 dinner, paid by Noah; three equal €20 shares.
// Revision 3: €30 taxi, paid by Lina; three equal €10 shares.
// Revision 4: dinner corrected to €66; three equal €22 shares.
// Revision 5: €20 external settlement recorded from Lina to Maya.
// Current: paid (€84, €66, €30), shares €60 each, settlement sent/received (€20).
export const currentBalances: readonly BalanceDisplay[] = [
  { participantId: "maya", amount: "+€4.00", direction: "owed" },
  { participantId: "noah", amount: "+€6.00", direction: "owed" },
  { participantId: "lina", amount: "−€10.00", direction: "owes" },
];

// At group revision 3, before the dinner correction and external settlement.
export const pastBalances: readonly BalanceDisplay[] = [
  { participantId: "maya", amount: "+€26.00", direction: "owed" },
  { participantId: "noah", amount: "+€2.00", direction: "owed" },
  { participantId: "lina", amount: "−€28.00", direction: "owes" },
];

export const expenses = [
  { id: "groceries", label: "Groceries for the house", category: "Food & drink", payer: "Maya", amount: "€84.00", symbol: "✳" },
  { id: "dinner", label: "Dinner at Flora", category: "Food & drink", payer: "Noah", amount: "€66.00", symbol: "◒" },
  { id: "taxi", label: "Ride home", category: "Transport", payer: "Lina", amount: "€30.00", symbol: "↗" },
] as const;

export const activity = [
  { id: 5, label: "External settlement recorded", detail: "Lina → Maya · €20.00", date: "14 Jul 2026 · 10:45", kind: "settlement" },
  { id: 4, label: "Dinner expense revised", detail: "€60.00 → €66.00 · revision 2", date: "13 Jul 2026 · 19:20", kind: "revision" },
  { id: 3, label: "Ride home added", detail: "€30.00 · paid by Lina", date: "13 Jul 2026 · 16:05", kind: "expense" },
  { id: 2, label: "Dinner at Flora added", detail: "€60.00 · paid by Noah", date: "12 Jul 2026 · 20:10", kind: "expense" },
  { id: 1, label: "Groceries added", detail: "€84.00 · paid by Maya", date: "12 Jul 2026 · 15:30", kind: "expense" },
] as const;

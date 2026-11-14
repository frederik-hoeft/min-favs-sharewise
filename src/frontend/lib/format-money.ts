/** Presentation-only conversion of an integer minor-unit amount to localized text. */
export function formatMoney(amountMinorUnits: number, currency: string): string {
  const formatter = new Intl.NumberFormat("en-IE", { style: "currency", currency });
  const fractionDigits = formatter.resolvedOptions().maximumFractionDigits ?? 2;
  return formatter.format(amountMinorUnits / 10 ** fractionDigits);
}

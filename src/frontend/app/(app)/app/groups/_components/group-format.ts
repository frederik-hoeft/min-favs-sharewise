export function memberCountLabel(count: number): string {
  return count === 1 ? "1 member" : `${count} members`;
}

export function formatDate(isoInstant: string): string {
  const date = new Date(isoInstant);
  return Number.isNaN(date.getTime()) ? isoInstant : new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(date);
}

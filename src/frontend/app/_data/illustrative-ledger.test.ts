import { describe, expect, it } from "vitest";
import { activity, currentBalances, expenses, pastBalances } from "./illustrative-ledger";

const euroCents = (display: string) => {
  const normalized = display.replace(/[€+−]/g, "");
  const sign = display.startsWith("−") ? -1 : 1;
  return sign * Math.round(Number(normalized) * 100);
};

describe("illustrative finance fixture", () => {
  it("reconciles to a zero-sum current and historical group position", () => {
    expect(currentBalances.reduce((sum, item) => sum + euroCents(item.amount), 0)).toBe(0);
    expect(pastBalances.reduce((sum, item) => sum + euroCents(item.amount), 0)).toBe(0);
    expect(expenses.reduce((sum, item) => sum + euroCents(item.amount), 0)).toBe(18_000);
  });

  it("shows the correction followed by an external settlement in group revision order", () => {
    expect(activity.map((item) => item.id)).toEqual([5, 4, 3, 2, 1]);
    expect(activity[0].label).toBe("External settlement recorded");
    expect(activity[1].detail).toContain("€60.00 → €66.00");
    expect(currentBalances.map((item) => euroCents(item.amount))).toEqual([400, 600, -1000]);
    expect(pastBalances.map((item) => euroCents(item.amount))).toEqual([2600, 200, -2800]);
  });
});

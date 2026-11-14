import { render, screen } from "@testing-library/react";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";
import { example } from "./_data/example";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("ShareWise landing page", () => {
  it("introduces the product with accessible public navigation and truthful finance previews", () => {
    renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /good company.*clear accounts/i })).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Footer navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /skip to content/i })).toHaveAttribute("href", "#main-content");
    expect(screen.getByRole("link", { name: /explore the workspace/i })).toHaveAttribute("href", "#workspace");
    expect(document.getElementById("workspace")).toBeInTheDocument();
    expect(document.getElementById("approach")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Illustrative ShareWise group workspace" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Illustrative expense and allocation details" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Illustrative group financial history" })).toBeInTheDocument();
    expect(screen.getByText(/sharewise does not send money or verify transfers/i)).toBeVisible();
    expect(screen.queryByRole("link", { name: /greeting/i })).not.toBeInTheDocument();
  });

  it("keeps the editorial fixture internally balanced", () => {
    const euros = (value: string) => Number(value.replace(/[^\d.,−+-]/g, "").replace("−", "-").replace(",", ""));
    const expenses = example.expenses.reduce((sum, expense) => sum + euros(expense.amount), 0);
    const shares = example.allocation.reduce((sum, share) => sum + euros(share.share), 0);
    const settlements = example.settlements.reduce((sum, settlement) => sum + euros(settlement.amount), 0);
    const net = example.people.reduce((sum, person) => sum + euros(person.balance), 0);

    expect(expenses).toBe(euros(example.totals.expenses));
    expect(settlements).toBe(euros(example.totals.settlements));
    expect(shares).toBe(320);
    expect(net).toBe(0);
    expect(example.history.map((entry) => entry.revision)).toEqual(["06", "05", "04", "03"]);
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom does not calculate layout or contrast; verify contrast in a real browser.
      rules: { "color-contrast": { enabled: false } },
    });
    expect(result.violations).toEqual([]);
  });
});

import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import { sampleGroup } from "./_components/preview-data";
import Home from "./page";
import { Providers } from "./providers";

function renderHome() {
  return render(
    <Providers>
      <Home />
    </Providers>,
  );
}

describe("Swiss Ledger landing-page candidate", () => {
  it("introduces the product and exposes working section destinations", () => {
    const { container } = renderHome();

    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    expect(screen.getByRole("heading", { level: 1, name: /good accounts.*better together/i })).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Main navigation" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main-content");

    for (const anchor of ["product", "approach", "history"]) {
      expect(container.querySelector(`#${anchor}`)).not.toBeNull();
      expect(screen.getByRole("navigation", { name: "Main navigation" }).querySelector(`a[href="#${anchor}"]`)).not.toBeNull();
    }
    expect(screen.getByRole("link", { name: "Explore the product" })).toHaveAttribute("href", "#product");
  });

  it("presents meaningful financial examples without claiming to process payments", () => {
    renderHome();

    const balanceTable = screen.getByRole("table", { name: "Illustrative group balances" });
    expect(within(balanceTable).getAllByRole("row")).toHaveLength(5);
    expect(within(balanceTable).getByText("+€390.00")).toBeInTheDocument();
    expect(within(balanceTable).getByText("−€350.00")).toBeInTheDocument();
    expect(screen.getByText("€1,400.00")).toBeVisible();
    expect(screen.getAllByText("€840.00", { selector: "strong" })).toHaveLength(2);
    expect(screen.getByText(/settlement records do not initiate or verify payments/i)).toBeInTheDocument();
    expect(screen.getAllByText("Groceries revised")).toHaveLength(2);
    expect(screen.getByText(/€220.00 → €240.00 · revision 2/)).toBeInTheDocument();
  });

  it("uses a zero-sum illustrative balance fixture", () => {
    expect(sampleGroup.participants.reduce((total, person) => total + person.balance, 0)).toBe(0);
    expect(sampleGroup.expenses.reduce((total, expense) => total + expense.amount, 0)).toBe(140000);
    expect(sampleGroup.settlement.sender).not.toBe(sampleGroup.settlement.recipient);
  });

  it("offers keyboard-accessible explicit system, light and dark preferences", async () => {
    const user = userEvent.setup();
    renderHome();

    const group = screen.getByRole("radiogroup", { name: "Color scheme" });
    const auto = within(group).getByRole("radio", { name: "Auto" });
    const light = within(group).getByRole("radio", { name: "Light" });
    const dark = within(group).getByRole("radio", { name: "Dark" });
    expect(auto).toBeChecked();

    await user.click(dark);
    expect(dark).toBeChecked();
    expect(document.documentElement.getAttribute("data-mantine-color-scheme")).toBe("dark");
    await user.click(light);
    expect(light).toBeChecked();
    expect(document.documentElement.getAttribute("data-mantine-color-scheme")).toBe("light");
    light.focus();
    await user.keyboard("{ArrowLeft}");
    expect(auto).toBeChecked();
  });

  it("has no detected structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom has no reliable layout/contrast calculation; manual browser check is separate.
      rules: { "color-contrast": { enabled: false } },
    });

    expect(result.violations).toEqual([]);
  });
});

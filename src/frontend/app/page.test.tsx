import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";
import { previewGroup } from "./_components/financeFixtures";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("ShareWise design candidate", () => {
  it("introduces the product and navigates to real page sections", async () => {
    const user = userEvent.setup();
    renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /shared money.*perfectly clear/i })).toBeVisible();
    const nav = screen.getByRole("navigation", { name: "Primary navigation" });
    const platform = within(nav).getByRole("link", { name: "Platform" });
    expect(platform).toHaveAttribute("href", "#platform");
    expect(document.querySelector("#platform")).toBeInTheDocument();
    await user.click(platform);
    expect(window.location.hash).toBe("#platform");
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main-content");
  });

  it("states the external-payment boundary and history semantics", () => {
    renderHome();
    expect(screen.getByRole("heading", { name: /we don.t move the money/i })).toBeVisible();
    expect(screen.getByText(/recorded here.*money moved elsewhere/i)).toBeVisible();
    expect(screen.getByText("PREVIOUS / V01")).toBeVisible();
    expect(screen.getByText("CURRENT / V02")).toBeVisible();
    expect(screen.getByText(/concept preview with fictional/i)).toBeVisible();
  });

  it("uses an internally consistent illustrative group ledger", () => {
    // This validates hand-authored illustration data, not a financial calculation feature.
    expect(previewGroup.participants.map((person) => person.amount).reduce((a, b) => a + b, 0)).toBe(0);
    expect(previewGroup.expenses.map((expense) => Number(expense.amount.replace(/[^0-9.]/g, ""))).reduce((a, b) => a + b, 0)).toBe(840);
    expect(previewGroup.activity.filter((event) => event.type === "settlement").map((event) => Number(event.amount.replace(/[^0-9.]/g, ""))).reduce((a, b) => a + b, 0)).toBe(150);
  });

  it("has no automated structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
    expect(result.violations).toEqual([]);
  });
});

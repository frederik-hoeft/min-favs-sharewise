import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";
import { displayMoney, previewGroup } from "./_components/preview-data";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("ShareWise landing page", () => {
  it("introduces the product and links only to real page sections", () => {
    renderHome();
    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /keep the plans.*lose the math/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /skip to content/i })).toHaveAttribute("href", "#main");

    const nav = screen.getByRole("navigation", { name: /main navigation/i });
    expect(within(nav).getByRole("link", { name: "The product" })).toHaveAttribute("href", "#product");
    expect(within(nav).getByRole("link", { name: "How it works" })).toHaveAttribute("href", "#how-it-works");
    expect(within(nav).getByRole("link", { name: "Our approach" })).toHaveAttribute("href", "#about");
    for (const id of ["product", "how-it-works", "about", "top", "main"]) {
      expect(document.getElementById(id)).toBeInTheDocument();
    }
    expect(screen.getByText("EXAMPLE VIEW")).toBeInTheDocument();
    expect(screen.getByText(/These are illustrative product views/i)).toBeInTheDocument();
  });

  it("switches between light, dark and system preferences", async () => {
    const user = userEvent.setup();
    for (const [label, scheme] of [["Dark", "dark"], ["Light", "light"], ["System", "auto"]] as const) {
      const view = renderHome();
      await user.click(screen.getByRole("button", { name: "Choose appearance" }));
      await user.click(await screen.findByRole("menuitem", { name: `${label} theme` }));
      expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe(scheme);
      if (scheme !== "auto") {
        expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", scheme);
      }
      view.unmount();
    }
  });

  it("uses consistent, zero-sum illustrative accounting without offering fake transactions", () => {
    renderHome();
    const { members, expenses, sharedCents, settlement } = previewGroup;
    expect(members.reduce((sum, member) => sum + member.balanceCents, 0)).toBe(0);
    expect(expenses.reduce((sum, expense) => sum + expense.amountCents, 0)).toBe(sharedCents);
    expect(sharedCents).toBe(68000);
    expect(settlement.amountCents).toBe(6000);
    // Test the fixed illustrative fixture, not a runtime finance feature.
    for (const member of members) {
      const paid = expenses.filter((expense) => expense.paidBy === member.name)
        .reduce((sum, expense) => sum + expense.amountCents, 0);
      const outgoing = member.name === settlement.sender ? settlement.amountCents : 0;
      const incoming = member.name === settlement.recipient ? settlement.amountCents : 0;
      expect(member.balanceCents).toBe(paid - sharedCents / members.length + outgoing - incoming);
    }
    expect(displayMoney(25000)).toContain("250");
    expect(screen.getByText("Noah paid Maya")).toBeInTheDocument();
    expect(screen.getByText(/No transfers through ShareWise/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /settle|pay|add expense|create group/i })).not.toBeInTheDocument();
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom cannot evaluate geometry or contrast; browser screenshots and inspection cover those.
      rules: { "color-contrast": { enabled: false } },
    });
    expect(result.violations).toEqual([]);
  });
});

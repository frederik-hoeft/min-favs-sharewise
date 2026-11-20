import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it, vi } from "vitest";
import Home from "@/app/(public)/page";
import PublicLayout from "@/app/(public)/layout";
import { displayMoney, previewGroup } from "@/app/(public)/_components/preview-data";
import { TestProviders } from "./support/TestProviders";

function renderHome() {
  return render(<TestProviders><PublicLayout><Home /></PublicLayout></TestProviders>);
}

describe("ShareWise landing page", () => {
  it("introduces the product and links only to real page sections", () => {
    renderHome();
    expect(screen.getAllByRole("main")).toHaveLength(1);
    expect(screen.getByRole("banner")).toBeInTheDocument();
    expect(screen.getByRole("contentinfo")).toBeInTheDocument();
    expect(screen.getAllByRole("banner")).toHaveLength(1);
    expect(screen.getByRole("heading", { level: 1, name: /keep the plans.*lose the math/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /skip to content/i })).toHaveAttribute("href", "#main");

    const nav = screen.getByRole("navigation", { name: /main navigation/i });
    expect(within(nav).getByRole("link", { name: "The product" })).toHaveAttribute("href", "#product");
    expect(within(nav).getByRole("link", { name: "How it works" })).toHaveAttribute("href", "#how-it-works");
    expect(within(nav).getByRole("link", { name: "Our approach" })).toHaveAttribute("href", "#about");
    for (const id of ["product", "how-it-works", "about", "top", "main"]) {
      expect(document.getElementById(id)).toBeInTheDocument();
    }
    const header = screen.getByRole("banner");
    expect(within(header).getByRole("link", { name: /sharewise, back to top/i })).toHaveAttribute("href", "#top");
    expect(within(header).getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    expect(within(header).getByRole("link", { name: "Get started" })).toHaveAttribute("href", "/register");
    const hero = screen.getByRole("region", { name: /keep the plans.*lose the math/i });
    const primaryAction = within(hero).getByRole("link", { name: "Get started" });
    expect(primaryAction).toHaveAttribute("href", "/register");
    expect(primaryAction.querySelector(".mantine-Button-root")?.getAttribute("style"))
      .toContain("--button-color: var(--sw-action-ink)");
    const secondaryAction = screen.getByRole("link", { name: /see how it works/i });
    expect(secondaryAction.style.getPropertyValue("--button-hover"))
      .toBe("var(--mantine-color-default-hover)");
    const closing = screen.getByRole("region", { name: /better together.*clearer with sharewise/i });
    const backToTop = within(closing).getByRole("link", { name: /back to top/i });
    expect(backToTop).toHaveAttribute("href", "#top");
    expect(backToTop.querySelector('svg[aria-hidden="true"]')).toBeInTheDocument();
    // Mantine white buttons use their filled color as label color. Gray was too pale.
    expect(backToTop.style.getPropertyValue("--button-color")).toBe("var(--mantine-color-dark-filled)");
    expect(screen.getByText("EXAMPLE VIEW")).toBeInTheDocument();
    // Phosphor glyphs decorate the previews without introducing false controls.
    expect(screen.getByText("Clear, equal allocations").closest("div")?.parentElement?.querySelector('svg[aria-hidden="true"]')).toBeInTheDocument();
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

  it("falls back gracefully from an invalid saved appearance preference", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem("mantine-color-scheme-value", "invalid-scheme");
    const view = renderHome();
    try {
      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Choose appearance" }));
      await user.click(await screen.findByRole("menuitem", { name: "Dark theme" }));
      expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
      expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
    } finally {
      view.unmount();
    }
  });

  it("keeps appearance controls usable when browser storage is denied", async () => {
    const user = userEvent.setup();
    const get = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new DOMException("Denied", "SecurityError"); });
    const set = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("Denied", "SecurityError"); });
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
    let view: ReturnType<typeof renderHome> | undefined;
    try {
      view = renderHome();
      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Choose appearance" }));
      await user.click(await screen.findByRole("menuitem", { name: "Dark theme" }));
      expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
      expect(get).toHaveBeenCalled();
      expect(set).toHaveBeenCalled();
      expect(warning).toHaveBeenCalled();
    } finally {
      view?.unmount();
      get.mockRestore();
      set.mockRestore();
      warning.mockRestore();
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

  it("renders the reusable financial panels as non-interactive illustrations", () => {
    renderHome();

    expect(screen.getAllByRole("article")).toHaveLength(3);
    expect(screen.getByRole("heading", { name: "Group balances" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Every expense, in its place." })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Paid in real life. Recorded here." })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Clarity, even when plans change." })).toBeInTheDocument();
    expect(screen.getAllByLabelText("Maya").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByLabelText("Noah").length).toBeGreaterThanOrEqual(1);
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

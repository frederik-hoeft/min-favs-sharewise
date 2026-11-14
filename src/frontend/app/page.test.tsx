import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Home from "./page";
import { preview } from "./_data/preview";
import { Providers } from "./providers";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

beforeEach(() => {
  window.localStorage.clear();
});

describe("ShareWise design candidate landing page", () => {
  it("introduces the product and links to real sections rather than starter/API routes", () => {
    renderHome();
    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /more together.*less to untangle/i })).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Main navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /take a closer look/i })).toHaveAttribute("href", "#inside");
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
    for (const id of ["main", "inside", "how-it-works", "history"]) {
      expect(document.getElementById(id)).toBeInTheDocument();
    }
    expect(document.querySelector('a[href^="/api/"]')).toBeNull();
    expect(document.querySelector('a[href="/greetings"]')).toBeNull();
  });

  it("labels all product previews as illustrative and does not offer fake finance actions", () => {
    renderHome();
    expect(screen.getByText("EXAMPLE VIEW")).toBeVisible();
    expect(screen.getAllByText(/External settlement recorded/i)).toHaveLength(2);
    expect(screen.getByText(/Transfer happened outside ShareWise/i)).toBeVisible();
    expect(screen.getByText(/Historical position before the dinner revision/i)).toBeVisible();
    expect(screen.queryByRole("button", { name: /settle|pay|add expense|invite/i })).toBeNull();
  });

  it("keeps the fictional financial preview internally balanced without calculating customer balances", () => {
    expect(preview.expenses.reduce((total, expense) => total + expense.amount, 0)).toBe(preview.totalExpenses);
    expect(preview.participants.reduce((total, person) => total + person.balance, 0)).toBe(0);
    expect(preview.participants.find((person) => person.name === "Maya")?.balance).toBe(255);
    expect(preview.historicalSnapshot.maya).toBe(317);
    expect(preview.allocationExample.share * preview.allocationExample.members).toBe(preview.allocationExample.amount);
  });

  it("offers keyboard-accessible system, dark and light themes", async () => {
    const user = userEvent.setup();
    renderHome();
    const selector = screen.getByRole("combobox", { name: "Appearance" });
    expect(selector).toHaveValue("auto");
    await user.selectOptions(selector, "dark");
    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark"));
    expect(selector).toHaveValue("dark");
    await user.selectOptions(selector, "light");
    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "light"));
    await user.selectOptions(selector, "auto");
    expect(selector).toHaveValue("auto");
  });

  it("keeps native mobile navigation keyboard operable", async () => {
    const user = userEvent.setup();
    renderHome();
    const summary = screen.getByLabelText("Open navigation menu");
    await user.click(summary);
    expect(summary.closest("details")).toHaveAttribute("open");
    expect(screen.getByRole("navigation", { name: "Mobile navigation" })).toHaveAttribute("aria-label", "Mobile navigation");
    await user.click(summary);
    expect(summary.closest("details")).not.toHaveAttribute("open");
  });

  it("falls back to the system scheme when stored preference cannot be read", () => {
    const blockedStorage = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("Storage access denied");
    });
    try {
      renderHome();
      expect(screen.getByRole("combobox", { name: "Appearance" })).toHaveValue("auto");
    } finally {
      blockedStorage.mockRestore();
    }
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom does not compute geometry or contrast; browser inspection covers those.
      rules: { "color-contrast": { enabled: false } },
    });
    expect(result.violations).toEqual([]);
  });
});

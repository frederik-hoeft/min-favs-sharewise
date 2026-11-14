import { render, screen, within } from "@testing-library/react";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("ShareWise landing page candidate", () => {
  it("introduces the product and contains navigable in-page destinations", () => {
    const { container } = renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /shared costs.*clear understanding/i })).toBeVisible();
    const nav = screen.getByRole("navigation", { name: "Primary navigation" });
    expect(within(nav).getAllByRole("link")).toHaveLength(3);

    for (const link of container.querySelectorAll<HTMLAnchorElement>('a[href^="#"]')) {
      const target = link.getAttribute("href")?.slice(1);
      expect(target, `Link ${link.textContent} has no target`).toBeTruthy();
      expect(document.getElementById(target!), `Missing target for ${link.textContent}`).not.toBeNull();
    }
  });

  it("shows plausible group expenses, allocations, external settlements and historical balances", () => {
    renderHome();

    expect(screen.getByText("Lake House Weekend")).toBeVisible();
    expect(screen.getByText("€240.00")).toBeVisible();
    expect(screen.getByText("€10.00")).toBeVisible();
    expect(screen.getByText("+€16.00")).toBeVisible();
    expect(screen.getByText("−€16.00")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Cost allocation" })).toBeVisible();
    expect(screen.getAllByText("€18.00")).toHaveLength(3);
    expect(screen.getByText(/Morgan sent €10.00 to Sam/)).toBeVisible();
    expect(screen.getByText(/€48.00 → €54.00/)).toBeVisible();
    expect(screen.getByText("Historical balances")).toBeVisible();
    expect(screen.getByText("+€18.00")).toBeVisible();
    expect(screen.getByText("−€30.00")).toBeVisible();
    expect(screen.getByText("+€12.00")).toBeVisible();
  });

  it("does not expose nonfunctional business controls as actionable buttons", () => {
    renderHome();

    // Hero/closing links are real anchors; all domain previews are static.
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(screen.getAllByRole("link").every((link) => link.getAttribute("href")?.startsWith("#"))).toBe(true);
    expect(screen.getByText(/product views above use illustrative data/)).toBeVisible();
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // Layout and color contrast need real-browser inspection.
      rules: { "color-contrast": { enabled: false } },
    });

    expect(result.violations).toEqual([]);
  });
});

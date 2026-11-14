import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { MantineProvider } from "@mantine/core";
import { cssVariablesResolver, theme } from "@/lib/theme";

function renderHome() {
  return render(<MantineProvider env="test" theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto"><Home /></MantineProvider>);
}

describe("ShareWise design candidate", () => {
  it("introduces the product, with working section links and semantic landmarks", () => {
    renderHome();
    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /good times.*clear tabs/i })).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main-content");

    const sectionIds = ["product", "approach", "history"];
    for (const id of sectionIds) {
      expect(document.getElementById(id)).toBeInTheDocument();
      expect(screen.getAllByRole("link").some((link) => link.getAttribute("href") === `#${id}`)).toBe(true);
    }
    expect(document.querySelectorAll('a[href^="/api/"], a[href="/greetings"]')).toHaveLength(0);
  });

  it("shows internally consistent, clearly illustrative domain examples", () => {
    renderHome();
    expect(screen.getByLabelText("Illustrative ShareWise group overview")).toHaveTextContent("+€215");
    expect(screen.getByLabelText("Illustrative ShareWise group overview")).toHaveTextContent("−€77");
    expect(screen.getByLabelText("Illustrative ShareWise group overview")).toHaveTextContent("−€51");
    expect(screen.getByLabelText("Illustrative ShareWise group overview")).toHaveTextContent("−€87");
    const expense = screen.getByLabelText("Illustrative expense and settlement previews");
    expect(within(expense).getByText("Allocated equally")).toBeVisible();
    expect(within(expense).getByText("Total allocated").parentElement).toHaveTextContent("€164.00");
    expect(within(expense).getByText("Not a payment confirmation")).toBeVisible();
    expect(screen.getByLabelText("Illustrative financial change history")).toHaveTextContent("Apartment expense revised");
    expect(screen.getByText(/not a live account/i)).toBeInTheDocument();
  });

  it("opens the accessible mobile navigation and points only to sections", async () => {
    const user = userEvent.setup();
    renderHome();
    await user.click(screen.getByRole("button", { name: "Open site navigation" }));
    const menu = screen.getByRole("menu", { name: "Open site navigation" });
    expect(within(menu).getByRole("menuitem", { name: "The product" })).toHaveAttribute("href", "#product");
    expect(within(menu).getByRole("menuitem", { name: "How it works" })).toHaveAttribute("href", "#approach");
    expect(within(menu).getByRole("menuitem", { name: "The full picture" })).toHaveAttribute("href", "#history");
  });

  it("offers light, dark and system modes and persists selection", async () => {
    const user = userEvent.setup();
    renderHome();
    const open = () => user.click(screen.getByRole("button", { name: "Appearance settings" }));
    await open();
    const menu = screen.getByRole("menu", { name: "Appearance settings" });
    expect(within(menu).getByRole("menuitem", { name: /system/i })).toHaveAttribute("aria-current", "true");
    await user.click(within(menu).getByRole("menuitem", { name: /dark/i }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
    await open();
    await user.click(screen.getByRole("menuitem", { name: /light/i }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("light");
    await open();
    await user.click(screen.getByRole("menuitem", { name: /system/i }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("auto");
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
    expect(result.violations).toEqual([]);
  });
});

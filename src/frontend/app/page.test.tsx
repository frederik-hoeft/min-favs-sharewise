import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("public landing page", () => {
  it("has meaningful static landmarks, section navigation, and representative finance content", () => {
    renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /more living.*less keeping.*score/i })).toBeVisible();
    const nav = screen.getByRole("navigation", { name: "Main navigation" });
    const productLink = screen.getByRole("link", { name: "Inside ShareWise" });
    expect(nav).toContainElement(productLink);
    expect(productLink).toHaveAttribute("href", "#product");
    expect(document.querySelector("#product")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main-content");
    expect(screen.getByRole("heading", { name: /dinner at flora/i })).toBeInTheDocument();
    expect(screen.getAllByText("External settlement recorded", { exact: true })).toHaveLength(2);
    expect(screen.getByText("Dinner expense revised")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Group positions at revision three" })).toBeInTheDocument();
    // Deliberately no faux add-expense, send-payment, sign-in, or backend links.
    expect(screen.queryByRole("button", { name: /send payment|add expense|sign in/i })).not.toBeInTheDocument();
  });

  it("supports switching light, dark and system via the accessible appearance menu", async () => {
    const user = userEvent.setup();
    renderHome();
    const menu = screen.getByRole("button", { name: "Appearance settings" });

    await user.click(menu);
    await user.click(await screen.findByRole("menuitem", { name: "Dark" }));
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
    await waitFor(() => expect(menu).toHaveAttribute("aria-expanded", "false"));

    await user.click(menu);
    await user.click(await screen.findByRole("menuitem", { name: "Light" }));
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "light");
    await waitFor(() => expect(menu).toHaveAttribute("aria-expanded", "false"));

    await user.click(menu);
    await user.click(await screen.findByRole("menuitem", { name: "System preference" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("auto");
  });

  it("opens and dismisses the appearance menu from the keyboard", async () => {
    const user = userEvent.setup();
    renderHome();
    const trigger = screen.getByRole("button", { name: "Appearance settings" });

    trigger.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("menuitem", { name: "System preference" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom cannot compute geometry or real colors; inspect screenshots separately.
      rules: { "color-contrast": { enabled: false } },
    });

    expect(result.violations).toEqual([]);
  });
});

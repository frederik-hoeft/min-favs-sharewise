import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("static ShareWise landing page", () => {
  it("introduces a coherent group-finance story with working section anchors", () => {
    renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /Good times[\s\S]*Clear tabs/ })).toBeVisible();
    expect(screen.getByText("€400", { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("figure", { name: /Illustrative ShareWise group overview/i })).toBeInTheDocument();
    expect(screen.getByRole("figure", { name: /Illustrative equal allocation/i })).toBeInTheDocument();
    expect(screen.getByRole("figure", { name: /Illustrative group financial history/i })).toBeInTheDocument();
    expect(screen.getByText(/actual money moves outside the app/i)).toBeVisible();

    const nav = screen.getByRole("navigation", { name: "Primary navigation" });
    for (const [label, href] of [
      ["Why ShareWise", "/#why-sharewise"],
      ["Inside the app", "/#inside"],
      ["The record", "/#the-record"],
    ]) {
      const link = within(nav).getByRole("link", { name: label });
      expect(link).toHaveAttribute("href", href);
      expect(document.querySelector(href.slice(1))).not.toBeNull();
    }
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main-content");
    expect(screen.getAllByText(/Illustrative|Concept preview/i).length).toBeGreaterThan(2);
  });

  it("opens and closes accessible mobile navigation", async () => {
    const user = userEvent.setup();
    renderHome();

    await user.click(screen.getByRole("button", { name: "Open menu" }));
    const mobileNav = await screen.findByRole("navigation", { name: "Mobile navigation" });
    expect(within(mobileNav).getByRole("link", { name: "Inside the app" })).toHaveAttribute("href", "/#inside");
    await user.click(within(mobileNav).getByRole("link", { name: "Inside the app" }));
    await waitFor(() => expect(screen.queryByRole("navigation", { name: "Mobile navigation" })).not.toBeInTheDocument());
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom cannot accurately compute contrast or geometry; browser review covers these.
      rules: { "color-contrast": { enabled: false } },
    });
    expect(result.violations).toEqual([]);
  });
});

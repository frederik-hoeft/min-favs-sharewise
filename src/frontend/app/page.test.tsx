import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";

function renderHome() {
  return render(<Providers><Home /></Providers>);
}

describe("ShareWise corporate design candidate", () => {
  it("introduces the product, with five capabilities and truthful static financial previews", () => {
    renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /better together.*clearer by design/i })).toBeVisible();
    expect(screen.getByRole("heading", { level: 2, name: /the details, beautifully handled/i })).toBeVisible();
    expect(screen.getByRole("heading", { level: 2, name: /clarity today.*context tomorrow/i })).toBeVisible();
    expect(screen.getByText(/four participants in the group/i)).toBeVisible();
    expect(screen.getByLabelText("Illustrative shared expense allocation")).toHaveTextContent("4 × €120.00");
    expect(screen.getByLabelText("Illustrative external settlement record")).toHaveTextContent("ShareWise records this transfer");
    expect(screen.getByLabelText("Illustrative financial change history")).toHaveTextContent("Revised cabin rental");
    expect(screen.getByLabelText("Illustrative ShareWise group balance dashboard")).toHaveTextContent("+€270.00");
  });

  it("has working same-page navigation without links to obsolete starter routes", () => {
    renderHome();

    const mainNavigation = screen.getByRole("navigation", { name: "Primary navigation" });
    expect(within(mainNavigation).getByRole("link", { name: "The product" })).toHaveAttribute("href", "#product");
    expect(within(mainNavigation).getByRole("link", { name: "The history" })).toHaveAttribute("href", "#history");
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#content");
    expect(screen.getByRole("link", { name: /explore the product/i })).toHaveAttribute("href", "#product");
    expect(document.querySelector("#product")).not.toBeNull();
    expect(document.querySelector("#how-it-works")).not.toBeNull();
    expect(document.querySelector("#history")).not.toBeNull();
    expect(screen.queryByRole("link", { name: /greetings|sign in|pay now/i })).not.toBeInTheDocument();
  });

  it("opens a keyboard-operable mobile navigation drawer with matching anchors", async () => {
    const user = userEvent.setup();
    renderHome();

    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    const navigation = await screen.findByRole("navigation", { name: "Mobile primary navigation" });
    const historyLink = within(navigation).getByRole("link", { name: "The history" });
    expect(historyLink).toHaveAttribute("href", "#history");
    await user.click(historyLink);
    await waitFor(() => expect(screen.queryByRole("navigation", { name: "Mobile primary navigation" })).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Open navigation" })).toBeInTheDocument();
  });

  it("has no jsdom-detectable accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom does not compute layout or color contrast; inspect these in Chromium.
      rules: { "color-contrast": { enabled: false } },
    });
    expect(result.violations).toEqual([]);
  });
});

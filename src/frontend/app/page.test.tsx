import { render, screen } from "@testing-library/react";
import axe from "axe-core";
import { describe, expect, it } from "vitest";
import Home from "./page";
import { Providers } from "./providers";

function renderHome() {
  return render(
    <Providers>
      <Home />
    </Providers>,
  );
}

describe("starter page", () => {
  it("introduces ShareWise in a semantic main region", () => {
    renderHome();

    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "ShareWise" })).toBeVisible();
    expect(screen.getByText("Group expense sharing and bookkeeping.")).toBeVisible();
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderHome();
    const result = await axe.run(container, {
      // jsdom does not calculate layout or contrast; verify contrast in a real browser.
      rules: { "color-contrast": { enabled: false } },
    });

    expect(result.violations).toEqual([]);
  });
});

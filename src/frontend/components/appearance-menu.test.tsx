import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AppearanceMenu } from "./appearance-menu";
import { MantineProvider } from "@mantine/core";
import { cssVariablesResolver, theme } from "@/lib/theme";

function renderMenu() {
  return render(
    <MantineProvider env="test" theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
      <AppearanceMenu />
    </MantineProvider>,
  );
}

describe("appearance preference fallback", () => {
  it("ignores invalid persisted preference and allows a fresh selection", async () => {
    window.localStorage.setItem("mantine-color-scheme-value", "invalid");
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Appearance settings" }));
    expect(screen.getByRole("menuitem", { name: "System" })).toHaveAttribute("aria-current", "true");
    await user.click(screen.getByRole("menuitem", { name: "Dark" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
  });
  it("restores a persisted valid preference", async () => {
    window.localStorage.setItem("mantine-color-scheme-value", "dark");
    renderMenu();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Appearance settings" }));
    expect(screen.getByRole("menuitem", { name: "Dark" })).toHaveAttribute("aria-current", "true");
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
  });

  it("keeps theme controls usable when storage is blocked", async () => {
    const get = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    const set = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      renderMenu();
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "Appearance settings" }));
      await user.click(screen.getByRole("menuitem", { name: "Dark" }));
      expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
    } finally {
      get.mockRestore();
      set.mockRestore();
      warning.mockRestore();
    }
  });
});

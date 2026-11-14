import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";
import { ColorSchemeMenu } from "./color-scheme-menu";

function renderMenu() {
  return render(<Providers><ColorSchemeMenu /></Providers>);
}

afterEach(() => vi.restoreAllMocks());

describe("ColorSchemeMenu", () => {
  it("offers light, dark and system preferences and updates the selected choice", async () => {
    const user = userEvent.setup();
    renderMenu();

    await user.click(screen.getByRole("button", { name: /appearance: system/i }));
    expect(screen.getByRole("menuitem", { name: "System (selected)" })).toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "Dark" }));
    expect(screen.getByRole("button", { name: /appearance: dark/i })).toBeInTheDocument();
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");

    await user.click(screen.getByRole("button", { name: /appearance: dark/i }));
    await user.click(screen.getByRole("menuitem", { name: "Light" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("light");

    await user.click(screen.getByRole("button", { name: /appearance: light/i }));
    await user.click(screen.getByRole("menuitem", { name: "System" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("auto");
  });

  it("does not crash if reading the stored preference is forbidden", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    expect(() => renderMenu()).not.toThrow();
    expect(screen.getByRole("button", { name: /choose color theme/i })).toBeInTheDocument();
  });

  it("recovers from an invalid stored preference", () => {
    window.localStorage.setItem("mantine-color-scheme-value", "invalid");
    renderMenu();
    expect(screen.getByRole("button", { name: /appearance: system/i })).toBeInTheDocument();
  });

  it("still changes appearance when preference storage cannot be written", async () => {
    const storageWarning = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: /choose color theme/i }));
    await user.click(screen.getByRole("menuitem", { name: "Dark" }));
    expect(screen.getByRole("button", { name: /appearance: dark/i })).toBeInTheDocument();
    expect(storageWarning).toHaveBeenCalledWith(
      expect.stringContaining("unable to save color scheme"),
      expect.any(Error),
    );
  });
});

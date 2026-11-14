import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";
import { ThemePicker } from "./theme-picker";

function mountPicker() {
  return render(<Providers><ThemePicker /></Providers>);
}

describe("theme preference", () => {
  it("supports system, light and dark with Mantine persistence", async () => {
    const user = userEvent.setup();
    mountPicker();
    expect(screen.getByRole("radiogroup", { name: "Color theme" })).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "Dark" }));
    expect(screen.getByRole("radio", { name: "Dark" })).toBeChecked();
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");

    await user.click(screen.getByRole("radio", { name: "Light" }));
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "light");

    await user.click(screen.getByRole("radio", { name: "Auto" }));
    expect(screen.getByRole("radio", { name: "Auto" })).toBeChecked();
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("auto");
  });

  it("ignores invalid stored preferences and retains a system-mode fallback", () => {
    window.localStorage.setItem("mantine-color-scheme-value", "invalid-preference");
    mountPicker();
    expect(screen.getByRole("radio", { name: "Auto" })).toBeChecked();
  });

  it("continues to work when storage writes fail", async () => {
    const user = userEvent.setup();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage disabled");
    });
    try {
      mountPicker();
      await user.click(screen.getByRole("radio", { name: "Dark" }));
      expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
      expect(warn).toHaveBeenCalled();
    } finally {
      setItem.mockRestore();
      warn.mockRestore();
    }
  });
});

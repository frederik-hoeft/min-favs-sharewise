import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";
import { ThemeSelector } from "./ThemeSelector";

function renderSelector() {
  return render(<Providers><ThemeSelector /></Providers>);
}

describe("theme preference control", () => {
  it("switches light/dark/system and persists the selected preference", async () => {
    const user = userEvent.setup();
    renderSelector();

    expect(screen.getByRole("radiogroup", { name: "Color theme" })).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "Dark" }));
    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark"));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");

    await user.click(screen.getByRole("radio", { name: "Light" }));
    await waitFor(() => expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "light"));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("light");

    await user.click(screen.getByRole("radio", { name: "System" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("auto");
  });

  it("restores a previously chosen preference", async () => {
    window.localStorage.setItem("mantine-color-scheme-value", "dark");
    renderSelector();
    await waitFor(() => expect(screen.getByRole("radio", { name: "Dark" })).toBeChecked());
  });

  it("remains usable when preference writes are blocked", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const write = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      const user = userEvent.setup();
      renderSelector();
      await user.click(screen.getByRole("radio", { name: "Dark" }));
      await waitFor(() => expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark"));
      expect(warn).toHaveBeenCalled();
    } finally {
      write.mockRestore();
      warn.mockRestore();
    }
  });
});

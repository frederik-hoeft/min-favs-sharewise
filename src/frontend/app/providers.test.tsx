import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ThemePicker } from "@/components/site-header/theme-picker";
import { Providers } from "./providers";

function renderThemePicker() {
  return render(<Providers><ThemePicker /></Providers>);
}

describe("Mantine appearance selection", () => {
  it("offers system, light and dark modes and persists explicit choices", async () => {
    const user = userEvent.setup();
    renderThemePicker();

    const appearance = screen.getByRole("combobox", { name: "Appearance" });
    expect(appearance).toHaveValue("auto");
    await user.selectOptions(appearance, "dark");
    expect(appearance).toHaveValue("dark");
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
    expect(localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
    await user.selectOptions(appearance, "light");
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "light");
    await user.selectOptions(appearance, "auto");
    expect(appearance).toHaveValue("auto");
  });

  it("restores a stored preference and ignores corrupt values", async () => {
    localStorage.setItem("mantine-color-scheme-value", "dark");
    const view = renderThemePicker();
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Appearance" })).toHaveValue("dark"));
    view.unmount();
    localStorage.setItem("mantine-color-scheme-value", "unexpected");
    renderThemePicker();
    expect(screen.getByRole("combobox", { name: "Appearance" })).toHaveValue("auto");
  });

  it("remains usable when preference storage throws", async () => {
    const user = userEvent.setup();
    const read = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw Error("blocked"); });
    const write = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw Error("blocked"); });
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      renderThemePicker();
      const appearance = screen.getByRole("combobox", { name: "Appearance" });
      expect(appearance).toHaveValue("auto");
      await user.selectOptions(appearance, "dark");
      expect(appearance).toHaveValue("dark");
      expect(warning).toHaveBeenCalled();
    } finally {
      read.mockRestore();
      write.mockRestore();
      warning.mockRestore();
    }
  });
});

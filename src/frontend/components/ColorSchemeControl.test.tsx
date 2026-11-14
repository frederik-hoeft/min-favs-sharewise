import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Providers } from "@/app/providers";
import { ColorSchemeControl } from "./ColorSchemeControl";
import { MobileNavigation } from "./MobileNavigation";

describe("accessible landing page controls", () => {
  it("offers light, dark and system preferences and persists selections", async () => {
    const user = userEvent.setup();
    render(<Providers><ColorSchemeControl /></Providers>);

    await user.click(screen.getByRole("button", { name: "Change color theme" }));
    expect(await screen.findAllByRole("menuitemradio", { hidden: true })).toHaveLength(3);
    await user.click(await screen.findByRole("menuitemradio", { name: /dark/i, hidden: true }));
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "dark");
    expect(localStorage.getItem("mantine-color-scheme-value")).toBe("dark");

    await user.click(screen.getByRole("button", { name: "Change color theme" }));
    expect(await screen.findByRole("menuitemradio", { name: /dark/i, hidden: true })).toHaveAttribute("aria-checked", "true");
    await user.click(await screen.findByRole("menuitemradio", { name: /light/i, hidden: true }));
    expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", "light");

    await user.click(screen.getByRole("button", { name: "Change color theme" }));
    await user.click(await screen.findByRole("menuitemradio", { name: /system/i, hidden: true }));
    expect(localStorage.getItem("mantine-color-scheme-value")).toBe("auto");
  });

  it("provides working section destinations in the mobile menu", async () => {
    const user = userEvent.setup();
    render(<Providers><MobileNavigation /></Providers>);
    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    expect(await screen.findByRole("menuitem", { name: "Platform", hidden: true })).toHaveAttribute("href", "#platform");
    expect(await screen.findByRole("menuitem", { name: "How it works", hidden: true })).toHaveAttribute("href", "#how-it-works");
    expect(await screen.findByRole("menuitem", { name: "Our approach", hidden: true })).toHaveAttribute("href", "#principles");
  });
});

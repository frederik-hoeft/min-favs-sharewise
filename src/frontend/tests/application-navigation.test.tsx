import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DEFAULT_THEME } from "@mantine/core";
import axe from "axe-core";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";
import { theme, cssVariablesResolver } from "@/lib/theme";
import { ApplicationShell } from "@/app/(app)/app/_components/ApplicationShell";
import { appBreadcrumbs } from "@/app/(app)/app/_components/app-routes";
import DummyOnePage from "@/app/(app)/app/dummy1/page";

let pathname = "/app/dummy1";
vi.mock("next/navigation", async (importOriginal) => ({
  ...await importOriginal<typeof import("next/navigation")>(),
  usePathname: () => pathname,
}));

function renderApp() {
  return render(
    <Providers>
      <ApplicationShell><DummyOnePage /></ApplicationShell>
    </Providers>,
  );
}

beforeEach(() => {
  pathname = "/app/dummy1";
});

describe("Application route shell", () => {
  it("provides semantic content, desktop navigation, active route and relative breadcrumbs", () => {
    renderApp();
    expect(screen.getAllByRole("main")).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#app-main");
    expect(screen.getByRole("heading", { name: "Dummy 1", level: 1 })).toBeInTheDocument();

    const navigation = screen.getByRole("navigation", { name: "Workspace navigation" });
    expect(within(navigation).getByRole("link", { name: "Dummy 1" })).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getByRole("link", { name: "Dummy 2" })).toHaveAttribute("href", "/app/dummy2");

    const breadcrumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(breadcrumbs).getByRole("link", { name: "Workspace" })).toHaveAttribute("href", "/app");
    expect(within(breadcrumbs).getByText("Dummy 1")).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "ShareWise workspace" })).toHaveAttribute("href", "/app");
  });

  it("uses a Mantine hamburger to open and close the accessible mobile flyout", async () => {
    const user = userEvent.setup();
    renderApp();

    const toggle = screen.getByRole("button", { name: "Open navigation" });
    expect(toggle).toHaveClass("mantine-Burger-root");
    expect(within(screen.getByRole("banner")).queryByLabelText("ShareWise")).not.toBeInTheDocument();
    await user.click(toggle);
    const drawer = await screen.findByRole("dialog", { name: "Workspace navigation" });
    expect(drawer).toBeInTheDocument();
    expect(within(drawer).getByRole("link", { name: "Dummy 2" })).toHaveAttribute("href", "/app/dummy2");
    expect(within(drawer).getByText("sharewise")).toBeInTheDocument();
    await user.click(within(drawer).getByRole("button", { name: "Close workspace navigation" }));
    expect(screen.getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-expanded", "false");

    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Workspace navigation" })).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-expanded", "false");
  });

  it("closes the compact rail after choosing a route, even when it remains hovered or focused", async () => {
    const user = userEvent.setup();
    renderApp();
    const navigation = screen.getByRole("navigation", { name: "Workspace navigation" });
    const rail = navigation.querySelector("[data-expanded]");
    expect(rail).not.toBeNull();
    const destination = within(navigation).getByRole("link", { name: "Dummy 2" });
    // jsdom has no Next.js router: prevent document navigation while exercising onClick.
    destination.addEventListener("click", (event) => event.preventDefault());

    fireEvent.pointerEnter(rail!, { pointerType: "mouse" });
    expect(rail).toHaveAttribute("data-expanded", "true");
    await user.click(destination);
    expect(rail).toHaveAttribute("data-expanded", "false");

    // The pointer is still over the rail. Only leaving and reentering may expand it.
    fireEvent.pointerEnter(rail!, { pointerType: "mouse" });
    expect(rail).toHaveAttribute("data-expanded", "false");
    fireEvent.pointerLeave(rail!, { pointerType: "mouse" });
    fireEvent.pointerEnter(rail!, { pointerType: "mouse" });
    expect(rail).toHaveAttribute("data-expanded", "true");

    fireEvent.pointerLeave(rail!, { pointerType: "mouse" });
    fireEvent.focus(destination);
    expect(rail).toHaveAttribute("data-expanded", "true");
    fireEvent.blur(destination, { relatedTarget: document.body });
    expect(rail).toHaveAttribute("data-expanded", "false");

    fireEvent.focus(destination);
    fireEvent.click(destination);
    expect(rail).toHaveAttribute("data-expanded", "false");
  });

  it("opens account options and exposes the appearance submenu", async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(await screen.findByRole("menuitem", { name: "Appearance" }));
    const back = screen.getByRole("menuitem", { name: "Back to account" });
    expect(back).toHaveFocus();
    await user.click(back);
    await user.click(await screen.findByRole("menuitem", { name: "Appearance" }));
    await user.click(await screen.findByRole("menuitem", { name: "Dark theme" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(await screen.findByRole("menuitem", { name: "Appearance" })).toBeInTheDocument();
  });

  it("keeps the header noninteractive outside explicit controls", async () => {
    const user = userEvent.setup();
    const scrollTo = vi.fn();
    vi.stubGlobal("scrollTo", scrollTo);
    try {
      renderApp();
      const header = screen.getByRole("banner");
      expect(within(header).queryByRole("button", { name: "Scroll to top" })).not.toBeInTheDocument();
      await user.click(header);
      expect(scrollTo).not.toHaveBeenCalled();
      expect(within(header).getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-controls", "mobile-app-navigation");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("configures shared NavLink and Menu styling through Mantine's theme", async () => {
    const user = userEvent.setup();
    renderApp();
    const roles = cssVariablesResolver(DEFAULT_THEME);
    expect(roles.light["--sw-interactive-hover"]).not.toBe(roles.dark["--sw-interactive-hover"]);
    expect(roles.light["--sw-nav-selected"]).not.toBe(roles.dark["--sw-nav-selected"]);
    expect(theme.components?.NavLink?.classNames).toBeDefined();
    expect(theme.components?.Menu?.styles).toMatchObject({
      dropdown: { background: "var(--sw-surface)", borderColor: "var(--sw-border)" },
      item: { "--menu-item-hover": "var(--sw-interactive-hover)" },
    });
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(await screen.findByRole("menuitem", { name: "Appearance" })).toBeInTheDocument();
  });

  it("has no detectable structural accessibility violations", async () => {
    const { container } = renderApp();
    const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
    expect(results.violations).toEqual([]);
  });
});

describe("Application breadcrumbs", () => {
  it("reflects the application URL instead of the route-group directory", () => {
    expect(appBreadcrumbs("/app")).toEqual([{ href: "/app", label: "Workspace" }]);
    expect(appBreadcrumbs("/app/dummy2")).toEqual([
      { href: "/app", label: "Workspace" },
      { href: "/app/dummy2", label: "Dummy 2" },
    ]);
    expect(appBreadcrumbs("/app/groups/group-history")).toEqual([
      { href: "/app", label: "Workspace" },
      { href: "/app/groups", label: "Groups" },
      { href: "/app/groups/group-history", label: "Group History" },
    ]);
  });
});

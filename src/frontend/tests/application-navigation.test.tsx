import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DEFAULT_THEME } from "@mantine/core";
import axe from "axe-core";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { theme, cssVariablesResolver } from "@/lib/theme";
import AppLayout from "@/app/(app)/app/layout";
import { IdentityProvider } from "@/app/identity-provider";
import { createIdentityRuntime, type IdentityRuntime } from "@/core/identity";
import { accountRoute, appBreadcrumbs, breadcrumbDemoRoutes } from "@/app/(app)/app/_components/app-routes";
import DummyOnePage from "@/app/(app)/app/dummy1/page";
import { TestProviders } from "./support/TestProviders";
import { authenticationResponse } from "./identity/support";

let pathname = "/app/dummy1";
let searchParams: URLSearchParams | null = null;
vi.mock("next/navigation", async (importOriginal) => ({
  ...await importOriginal<typeof import("next/navigation")>(),
  usePathname: () => pathname,
  useSearchParams: () => searchParams,
  useRouter: () => ({ replace: vi.fn() }),
}));

const appRuntimes: IdentityRuntime[] = [];

function renderApp() {
  const runtime = createIdentityRuntime({ channel: () => undefined, fetchImpl: vi.fn() });
  runtime.session.accept(authenticationResponse("signed-in"));
  appRuntimes.push(runtime);
  return render(
    <TestProviders>
      <IdentityProvider runtime={runtime}><AppLayout><DummyOnePage /></AppLayout></IdentityProvider>
    </TestProviders>,
  );
}

afterEach(() => { for (const runtime of appRuntimes.splice(0)) runtime.dispose(); });

beforeEach(() => {
  pathname = "/app/dummy1";
  searchParams = null;
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
    expect(within(navigation).getByRole("link", { name: "Dummy 3" })).toHaveAttribute("href", "/app/dummy3");

    const breadcrumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    const parentCrumb = within(breadcrumbs).getByRole("link", { name: "Workspace" });
    const currentCrumb = within(breadcrumbs).getByText("Dummy 1");
    expect(parentCrumb).toHaveAttribute("href", "/app");
    expect(parentCrumb).toHaveAttribute("data-size", "sm");
    expect(currentCrumb).toHaveAttribute("aria-current", "page");
    expect(currentCrumb).toHaveAttribute("data-size", "sm");
    expect(screen.getByRole("link", { name: "ShareWise workspace" })).toHaveAttribute("href", "/app");
  });

  it("keeps deep routes navigable through collapsed breadcrumbs", async () => {
    pathname = breadcrumbDemoRoutes.overview;
    const user = userEvent.setup();
    renderApp();

    const breadcrumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(breadcrumbs).getByRole("link", { name: "Workspace" })).toHaveAttribute("href", "/app");
    expect(breadcrumbs.querySelector('[aria-current="page"]')).toHaveTextContent("Overview");
    const disclosure = within(breadcrumbs).getByRole("button", { name: "Show 3 omitted breadcrumb locations" });
    await user.click(disclosure);
    expect(await screen.findByRole("menuitem", { name: "Dummy 3" })).toHaveAttribute("href", "/app/dummy3");
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

    const accountButton = screen.getByRole("button", { name: "Account menu" });
    expect(accountButton).toHaveClass("mantine-ActionIcon-root");
    expect(accountButton.style.getPropertyValue("--ai-bg")).toBe("transparent");
    expect(accountButton.style.getPropertyValue("--ai-hover")).toBe("var(--mantine-color-default-hover)");
    await user.click(accountButton);
    await user.click(await screen.findByRole("menuitem", { name: "Appearance" }));
    const back = screen.getByRole("menuitem", { name: "Back to account" });
    expect(back).not.toHaveFocus(); // Mouse navigation must not create sticky focused hover styling.
    await user.click(back);
    const appearance = await screen.findByRole("menuitem", { name: "Appearance" });
    expect(appearance).not.toHaveFocus();

    // Keyboard navigation still restores focus when switching menu views.
    appearance.focus();
    await user.keyboard("{Enter}");
    const keyboardBack = screen.getByRole("menuitem", { name: "Back to account" });
    expect(keyboardBack).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("menuitem", { name: "Appearance" })).toHaveFocus();

    await user.click(screen.getByRole("menuitem", { name: "Appearance" }));
    await user.click(await screen.findByRole("menuitem", { name: "Dark theme" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(await screen.findByRole("menuitem", { name: "Appearance" })).toBeInTheDocument();
  });

  it("navigates to the account route from the menu without disturbing Appearance or mobile navigation", async () => {
    pathname = accountRoute.href;
    const user = userEvent.setup();
    renderApp();
    const breadcrumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(breadcrumbs).getByRole("link", { name: "Workspace" })).toHaveAttribute("href", "/app");
    expect(within(breadcrumbs).getByText("Account")).toHaveAttribute("aria-current", "page");

    await user.click(screen.getByRole("button", { name: "Account menu" }));
    const accountItem = await screen.findByRole("menuitem", { name: "Account details" });
    expect(accountItem).toHaveAttribute("href", accountRoute.href);
    accountItem.addEventListener("click", (event) => event.preventDefault());
    await user.click(accountItem);
    await waitFor(() => expect(screen.queryByRole("menuitem", { name: "Account details" })).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Account menu" }));
    const appearance = await screen.findByRole("menuitem", { name: "Appearance" });
    appearance.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByRole("menuitem", { name: "Back to account" })).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("menuitem", { name: "Appearance" })).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = await screen.findByRole("dialog", { name: "Workspace navigation" });
    expect(within(drawer).getByRole("link", { name: "Dummy 1" })).toBeInTheDocument();
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
    // Mantine's own NavLink inactive hover uses gray-0 and dark-6.
    expect(theme.colors?.gray?.[0]).toBe(roles.light["--sw-interactive-hover"]);
    expect(theme.colors?.dark?.[6]).toBe(roles.dark["--sw-interactive-hover"]);
    expect(roles.light["--mantine-color-default-hover"]).toBe("var(--sw-interactive-hover)");
    expect(roles.dark["--mantine-color-default-hover"]).toBe("var(--sw-interactive-hover)");
    expect(theme.components?.NavLink?.defaultProps).toMatchObject({ variant: "filled", autoContrast: true });
    expect(theme.components?.Menu?.styles).toMatchObject({
      dropdown: { background: "var(--sw-surface)", borderColor: "var(--sw-border)" },
      item: { "--menu-item-hover": "var(--mantine-color-default-hover)" },
    });
    expect(theme.components?.Burger?.defaultProps).toMatchObject({ color: "var(--mantine-color-text)" });
    expect(theme.components?.Burger?.styles).toMatchObject({
      root: { alignItems: "center", justifyContent: "center", minHeight: "2.75rem" },
    });
    const selected = within(screen.getByRole("navigation", { name: "Workspace navigation" }))
      .getByRole("link", { name: "Dummy 1" });
    expect(selected.style.getPropertyValue("--nl-bg")).toBe("var(--mantine-color-indigoBrand-filled)");
    expect(selected.style.getPropertyValue("--nl-color")).toBe("var(--sw-action-ink)");
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
    expect(appBreadcrumbs(breadcrumbDemoRoutes.overview)).toEqual([
      { href: "/app", label: "Workspace" },
      { href: "/app/dummy3", label: "Dummy 3" },
      { href: breadcrumbDemoRoutes.group, label: "International Collaboration Project" },
      { href: breadcrumbDemoRoutes.history, label: "Shared Expenses And Settlement Revision History" },
      { href: breadcrumbDemoRoutes.overview, label: "Overview" },
    ]);
    expect(appBreadcrumbs("/app/groups/group-history")).toEqual([
      { href: "/app", label: "Workspace" },
      { href: "/app/groups", label: "Groups" },
      { href: "/app/groups/group-history", label: "Group History" },
    ]);
  });
});

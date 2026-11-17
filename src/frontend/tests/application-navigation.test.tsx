import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";
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

  it("uses the brand symbol to open and close the accessible mobile flyout", async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = await screen.findByRole("dialog", { name: "Workspace navigation" });
    expect(drawer).toBeInTheDocument();
    expect(within(drawer).getByRole("link", { name: "Dummy 2" })).toHaveAttribute("href", "/app/dummy2");
    await user.click(screen.getByRole("button", { name: "Close navigation" }));
    expect(screen.getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-expanded", "false");

    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Workspace navigation" })).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-expanded", "false");
  });

  it("opens the account menu and changes the shared appearance preference", async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(screen.getByRole("button", { name: "Account and appearance" }));
    await user.click(await screen.findByRole("menuitem", { name: "Dark theme" }));
    expect(window.localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
  });

  it("scrolls to the top when its otherwise empty header surface is clicked", async () => {
    const scrollTo = vi.fn();
    vi.stubGlobal("scrollTo", scrollTo);
    try {
      const user = userEvent.setup();
      renderApp();
      await user.click(screen.getByRole("button", { name: "Scroll to top" }));
      expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "smooth" });
      scrollTo.mockClear();
      await user.click(screen.getByRole("button", { name: "Account and appearance" }));
      expect(scrollTo).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
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

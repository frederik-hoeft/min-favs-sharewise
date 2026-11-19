import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";
import { MantineProvider } from "@mantine/core";
import { theme, cssVariablesResolver } from "@/lib/theme";
import { ResponsiveBreadcrumbs } from "@/app/(app)/app/_components/ResponsiveBreadcrumbs";
import type { AppBreadcrumb } from "@/app/(app)/app/_components/app-routes";

const path: AppBreadcrumb[] = [
  { href: "/app", label: "Workspace" },
  { href: "/app/groups", label: "Groups" },
  { href: "/app/groups/a-long-middle-segment", label: "A very long intermediate breadcrumb label" },
  { href: "/app/groups/a-long-middle-segment/expenses", label: "Expenses" },
];

const observers: Array<() => void> = [];
let available = 240;
let required = 720;

class ResizeObserverStub {
  constructor(private readonly callback: () => void) { observers.push(callback); }
  observe() { /* Measurements are triggered explicitly by the test. */ }
  disconnect() { /* No live resize sources. */ }
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  observers.length = 0;
});

function renderWithMeasurements() {
  vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
    const width = this.getAttribute("aria-label") === "Breadcrumb" ? available : required;
    return { x: 0, y: 0, top: 0, left: 0, bottom: 24, right: width, width, height: 24, toJSON: () => ({}) };
  });
  return render(<Providers><ResponsiveBreadcrumbs crumbs={path} /></Providers>);
}

describe("Responsive breadcrumb trail", () => {
  it("collapses only when the full path exceeds the available header width", () => {
    available = 1200;
    required = 720;
    renderWithMeasurements();
    const nav = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(nav).getByRole("link", { name: "Groups" })).toHaveAttribute("href", "/app/groups");
    expect(within(nav).getByRole("link", { name: "A very long intermediate breadcrumb label" })).toBeInTheDocument();
    expect(within(nav).queryByRole("button", { name: /omitted breadcrumb/i })).not.toBeInTheDocument();

    available = 240;
    act(() => observers.forEach((callback) => callback()));
    expect(within(nav).getByRole("link", { name: "Workspace" })).toHaveAttribute("href", "/app");
    expect(within(nav).queryByRole("link", { name: "Groups" })).not.toBeInTheDocument();
    expect(nav.querySelector('[aria-current="page"]')).toHaveTextContent("Expenses");
    expect(within(nav).getByRole("button", { name: /2 omitted breadcrumb locations/i })).toBeInTheDocument();

    available = 1200;
    act(() => observers.forEach((callback) => callback()));
    expect(within(nav).getByRole("link", { name: "Groups" })).toBeInTheDocument();
  });

  it("keeps omitted ancestors navigable from a keyboard-accessible menu", async () => {
    available = 200;
    required = 900;
    const user = userEvent.setup();
    render(<MantineProvider env="test" theme={theme} cssVariablesResolver={cssVariablesResolver}>
      <ResponsiveBreadcrumbs crumbs={path} />
    </MantineProvider>);

    const disclosure = screen.getByRole("button", { name: "Show 2 omitted breadcrumb locations" });
    await user.click(disclosure);
    expect(await screen.findByRole("menuitem", { name: "Groups" })).toHaveAttribute("href", "/app/groups");
    expect(screen.getByRole("menuitem", { name: "A very long intermediate breadcrumb label" }))
      .toHaveAttribute("href", "/app/groups/a-long-middle-segment");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("keeps an ordinary two-segment trail visible without a disclosure", () => {
    available = 100;
    required = 900;
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
    render(<Providers><ResponsiveBreadcrumbs crumbs={path.slice(0, 2)} /></Providers>);
    const nav = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(nav).getByRole("link", { name: "Workspace" })).toBeInTheDocument();
    expect(within(nav).getByText("Groups")).toHaveAttribute("aria-current", "page");
    expect(within(nav).queryByRole("button")).not.toBeInTheDocument();
  });
});

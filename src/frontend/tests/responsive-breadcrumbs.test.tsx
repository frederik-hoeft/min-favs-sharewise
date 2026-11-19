import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResponsiveBreadcrumbs } from "@/app/(app)/app/_components/ResponsiveBreadcrumbs";
import type { AppBreadcrumb } from "@/app/(app)/app/_components/app-routes";
import { TestProviders } from "./support/TestProviders";

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
  constructor(private readonly callback: () => void) {}
  observe(target: Element) {
    // Only trigger the breadcrumb measurements, not Floating UI's observers.
    if (target.getAttribute("aria-label") === "Breadcrumb" || target.hasAttribute("aria-current")) {
      observers.push(this.callback);
    }
  }
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
  return render(<TestProviders><ResponsiveBreadcrumbs crumbs={path} /></TestProviders>);
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
    render(<TestProviders>
      <ResponsiveBreadcrumbs crumbs={path} />
    </TestProviders>);

    const disclosure = screen.getByRole("button", { name: "Show 2 omitted breadcrumb locations" });
    await user.click(disclosure);
    expect(await screen.findByRole("menuitem", { name: "Groups" })).toHaveAttribute("href", "/app/groups");
    expect(screen.getByRole("menuitem", { name: "A very long intermediate breadcrumb label" }))
      .toHaveAttribute("href", "/app/groups/a-long-middle-segment");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("keeps an overlong current label to one line and reveals its full text on activation", async () => {
    available = 230;
    required = 870;
    const longLabel = "Shared Expenses And Settlement Revision History";
    let labelWidth = 85;

    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(function (this: HTMLElement) {
      return this.getAttribute("aria-current") === "page" ? labelWidth : 0;
    });
    vi.spyOn(HTMLElement.prototype, "scrollWidth", "get").mockImplementation(function (this: HTMLElement) {
      return this.getAttribute("aria-current") === "page" ? 380 : 0;
    });

    const user = userEvent.setup();
    render(<TestProviders>
      <ResponsiveBreadcrumbs crumbs={[...path.slice(0, 3), { href: `${path[2].href}/history`, label: longLabel }]} />
    </TestProviders>);

    const nav = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(nav.querySelector('[aria-current="page"]')).toHaveTextContent(longLabel);
    const reveal = within(nav).getByRole("button", { name: `Show full location: ${longLabel}` });
    await user.click(reveal);
    expect(within(await screen.findByRole("dialog")).getByText(longLabel)).toBeInTheDocument();
    await user.keyboard("{Escape}");

    // When the label fits again, the plain current crumb has no extra control.
    labelWidth = 500;
    act(() => observers.forEach((callback) => callback()));
    expect(within(nav).queryByRole("button", { name: /show full location/i })).not.toBeInTheDocument();
    expect(nav.querySelector('[aria-current="page"]')).toHaveTextContent(longLabel);
  });

  it("does not oscillate between truncated and full text during repeated resizes", () => {
    available = 200;
    required = 900;
    const longLabel = "Shared Expenses And Settlement Revision History";
    let labelWidth = 85;

    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
    // A regression guard for the original loop: moving the measured label
    // into a button changes its available width and could flip truncation on
    // every render. The measurement must stay independent of the disclosure.
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(function (this: HTMLElement) {
      if (this.getAttribute("aria-current") !== "page") return 0;
      return this.closest("button") ? 500 : labelWidth;
    });
    vi.spyOn(HTMLElement.prototype, "scrollWidth", "get").mockImplementation(function (this: HTMLElement) {
      return this.getAttribute("aria-current") === "page" ? 380 : 0;
    });

    render(<TestProviders>
      <ResponsiveBreadcrumbs crumbs={[...path.slice(0, 3), { href: `${path[2].href}/history`, label: longLabel }]} />
    </TestProviders>);

    const nav = screen.getByRole("navigation", { name: "Breadcrumb" });
    const current = nav.querySelector('[aria-current="page"]');
    const reveal = () => within(nav).queryByRole("button", { name: `Show full location: ${longLabel}` });
    expect(reveal()).toBeInTheDocument();

    // Repeated observer notifications (including unchanged widths) must not
    // trigger a render loop or move the measured text into the control.
    for (let index = 0; index < 24; index++) {
      labelWidth = index % 3 === 0 ? 500 : 85;
      act(() => observers.forEach((callback) => callback()));
      expect(Boolean(reveal())).toBe(labelWidth < 380);
      expect(nav.querySelector('[aria-current="page"]')).toBe(current);
    }
  });

  it("keeps an ordinary two-segment trail visible without a disclosure", () => {
    available = 100;
    required = 900;
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
    render(<TestProviders><ResponsiveBreadcrumbs crumbs={path.slice(0, 2)} /></TestProviders>);
    const nav = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(nav).getByRole("link", { name: "Workspace" })).toBeInTheDocument();
    expect(within(nav).getByText("Groups")).toHaveAttribute("aria-current", "page");
    expect(within(nav).queryByRole("button")).not.toBeInTheDocument();
  });
});

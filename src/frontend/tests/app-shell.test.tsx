import { AppShell, AppShellMain } from "@mantine/core";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ShellHeader } from "@/components/navigation/ShellHeader";
import { TestProviders } from "./support/TestProviders";

describe("Shared shell header", () => {
  it("can host independent application navigation without public-page content", () => {
    render(
      <TestProviders>
        <AppShell header={{ height: "6rem" }} padding={0}>
          <ShellHeader>
            <nav aria-label="Group navigation"><a href="#account">Account</a></nav>
          </ShellHeader>
          <AppShellMain id="account"><h1>Group overview</h1></AppShellMain>
        </AppShell>
      </TestProviders>,
    );

    const header = screen.getByRole("banner");
    expect(within(header).getByRole("navigation", { name: "Group navigation" })).toBeInTheDocument();
    expect(within(header).getByRole("link", { name: "Account" })).toHaveAttribute("href", "#account");
    expect(screen.getByRole("main")).toContainElement(screen.getByRole("heading", { name: "Group overview" }));
    expect(screen.queryByRole("link", { name: "The product" })).not.toBeInTheDocument();
  });
});

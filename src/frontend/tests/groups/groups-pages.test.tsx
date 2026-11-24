import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";
import { createIdentityRuntime, type IdentityRuntime } from "@/core/identity";
import GroupsPage from "@/app/(app)/app/groups/page";
import NewGroupPage from "@/app/(app)/app/groups/new/page";
import GroupViewPage from "@/app/(app)/app/groups/view/page";
import { authenticationResponse } from "../identity/support";
import { TestProviders } from "../support/TestProviders";
import { GROUP_ID, details, json, problem, scriptedClient, summary } from "./support";

const LIST = "/api/groups/v1/groups";
const GROUP = `${LIST}/${GROUP_ID}`;

let fake = scriptedClient();
// Pages obtain their client from the workspace guard's session facade, as in production; its transport is scripted.
let runtime: IdentityRuntime = createIdentityRuntime({ fetchImpl: fake.fetchImpl });
let search = "";
const push = vi.fn();
const replace = vi.fn();

vi.mock("next/navigation", async (importOriginal) => ({
  ...await importOriginal<typeof import("next/navigation")>(),
  usePathname: () => "/app/groups",
  useRouter: () => ({ push, replace, back: vi.fn(), forward: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }),
  useSearchParams: () => new URLSearchParams(search),
}));

beforeEach(() => {
  fake = scriptedClient();
  runtime = createIdentityRuntime({ fetchImpl: fake.fetchImpl });
  runtime.session.accept(authenticationResponse("group-access-token"));
  search = "";
  push.mockReset();
  replace.mockReset();
});

afterEach(() => runtime.dispose());

function renderPage(page: ReactElement) {
  return render(
    <TestProviders><IdentityProvider runtime={runtime}><RequireAuthentication>{page}</RequireAuthentication></IdentityProvider></TestProviders>,
  );
}

/** Mantine associates field errors through aria-describedby and marks the input invalid. */
function expectFieldError(field: HTMLElement, message: string) {
  expect(field).toHaveAttribute("aria-invalid", "true");
  expect(field).toHaveAccessibleDescription(expect.stringContaining(message));
}

async function expectNoAxeViolations(container: HTMLElement) {
  const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
  expect(results.violations).toEqual([]);
}

describe("GM-FT01 group list", () => {
  it("lists groups with links, member counts and role badges", async () => {
    fake.respond("GET", LIST, json(200, { items: [
      summary(),
      summary({ id: "0193a5c4-7e1f-7c2a-9b3d-000000000002", name: "Ski trip", description: null, memberCount: 1, callerRole: "member" }),
    ] }));
    renderPage(<GroupsPage />);

    const list = await screen.findByRole("list", { name: "Your groups" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByRole("link", { name: "Flat share" })).toHaveAttribute("href", `/app/groups/view?id=${GROUP_ID}`);
    expect(within(items[0]).getByText("Rent and groceries")).toBeInTheDocument();
    expect(within(items[0]).getByText("3 members")).toBeInTheDocument();
    expect(within(items[0]).getByLabelText("Your role: Administrator")).toBeInTheDocument();
    expect(within(items[1]).getByText("1 member")).toBeInTheDocument();
    expect(within(items[1]).getByLabelText("Your role: Member")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create group" })).toHaveAttribute("href", "/app/groups/new");
  });

  it("shows an empty state that still offers creation", async () => {
    fake.respond("GET", LIST, json(200, { items: [] }));
    renderPage(<GroupsPage />);

    expect(await screen.findByRole("heading", { name: "No groups yet" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create group" })).toBeInTheDocument();
  });

  it("leaves an expired sign-in to the workspace guard instead of showing group data", async () => {
    fake.respond("GET", LIST, json(200, { items: [summary()] }));
    renderPage(<GroupsPage />);
    expect(await screen.findByRole("link", { name: "Flat share" })).toBeInTheDocument();

    await act(async () => runtime.session.invalidate("expired"));
    expect(screen.queryByRole("link", { name: "Flat share" })).not.toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Fgroups"));
  });

  it("shows account-not-available for 403 group_user_unknown", async () => {
    fake.respond("GET", LIST, problem(403, "group_user_unknown"));
    renderPage(<GroupsPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Account not yet available");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("sends the session's IAM bearer with Group requests", async () => {
    fake.respond("GET", LIST, json(200, { items: [] }));
    renderPage(<GroupsPage />);

    expect(await screen.findByRole("heading", { name: "No groups yet" })).toBeInTheDocument();
    const [, init] = vi.mocked(fake.fetchImpl).mock.calls[0];
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer group-access-token");
  });

  it("offers a retry after a network error", async () => {
    const user = userEvent.setup();
    fake.respond("GET", LIST, new TypeError("offline"), json(200, { items: [summary()] }));
    renderPage(<GroupsPage />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Groups are unavailable");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("link", { name: "Flat share" })).toBeInTheDocument();
  });
});

describe("GM-FT02 group creation", () => {
  it("validates on the client with the server's limits before sending", async () => {
    const user = userEvent.setup();
    renderPage(<NewGroupPage />);
    const name = screen.getByRole("textbox", { name: /Name/ });
    const description = screen.getByRole("textbox", { name: /Description/ });

    await user.click(screen.getByRole("button", { name: "Create group" }));
    expectFieldError(name, "Enter a group name.");

    await user.type(name, "\u{1F3D4}".repeat(65));
    await user.click(description);
    await user.paste("a\tb");
    await user.click(screen.getByRole("button", { name: "Create group" }));
    expectFieldError(name, "Use at most 64 characters.");
    expectFieldError(description, "Line breaks are allowed; remove tabs and other control characters.");
    expect(fake.requests).toEqual([]);
  });

  it("shows server field errors on their fields", async () => {
    const user = userEvent.setup();
    fake.respond("POST", LIST, problem(400, "validation_failed", {
      fieldErrors: [{ field: "name", code: "too_long", message: "Name is too long." }],
    }));
    renderPage(<NewGroupPage />);

    await user.type(screen.getByRole("textbox", { name: /Name/ }), "Flat share");
    await user.click(screen.getByRole("button", { name: "Create group" }));
    expect(await screen.findByText("Name is too long.")).toBeInTheDocument();
    expectFieldError(screen.getByRole("textbox", { name: /Name/ }), "Name is too long.");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it("creates the group and navigates to its detail page", async () => {
    const user = userEvent.setup();
    fake.respond("POST", LIST, json(201, details()));
    renderPage(<NewGroupPage />);

    await user.type(screen.getByRole("textbox", { name: /Name/ }), " Flat share ");
    await user.type(screen.getByRole("textbox", { name: /Description/ }), "   ");
    await user.click(screen.getByRole("button", { name: "Create group" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/app/groups/view?id=${GROUP_ID}`));
    expect(fake.requests).toEqual([{ method: "POST", path: LIST, body: { name: " Flat share ", description: null } }]);
  });

  it("does not claim failure when a creation outcome is uncertain", async () => {
    const user = userEvent.setup();
    fake.respond("POST", LIST, problem(500, "internal_error"));
    renderPage(<NewGroupPage />);

    await user.type(screen.getByRole("textbox", { name: /Name/ }), "Flat share");
    await user.click(screen.getByRole("button", { name: "Create group" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("could not confirm that the group was created");
  });
});

describe("GM-FT03 group details", () => {
  it("offers editing only to administrators", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", GROUP, json(200, details({ callerRole: "member" })));
    const first = renderPage(<GroupViewPage />);
    expect(await screen.findByRole("heading", { name: "Flat share", level: 1 })).toBeInTheDocument();
    expect(screen.getByText("3 members")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
    first.unmount();

    fake.respond("GET", GROUP, json(200, details()));
    renderPage(<GroupViewPage />);
    expect(await screen.findByRole("button", { name: "Edit details" })).toBeInTheDocument();
  });

  it("keeps input on a 409 until the user loads the current metadata, then saves against it", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    fake.respond("GET", GROUP,
      json(200, details()),
      json(200, details({ name: "Shared flat", description: null, version: 1 })));
    fake.respond("PUT", GROUP,
      problem(409, "group_version_conflict"),
      json(200, details({ name: "Shared flat 2026", description: null, version: 2 })));
    renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    const name = screen.getByRole("textbox", { name: /Name/ });
    await user.clear(name);
    await user.type(name, "My edit");
    await user.click(screen.getByRole("button", { name: "Save changes" }));

    const conflict = await screen.findByRole("alert");
    expect(conflict).toHaveTextContent("This group was changed in the meantime");
    expect(name).toHaveValue("My edit");
    expect(fake.requests.at(-1)).toEqual({ method: "PUT", path: GROUP, body: { name: "My edit", description: "Rent and groceries", expectedVersion: 0 } });

    await user.click(within(conflict).getByRole("button", { name: "Load current details" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: /Name/ })).toHaveValue("Shared flat"));
    expect(screen.queryByText("This group was changed in the meantime")).not.toBeInTheDocument();

    await user.clear(screen.getByRole("textbox", { name: /Name/ }));
    await user.type(screen.getByRole("textbox", { name: /Name/ }), "Shared flat 2026");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { name: "Shared flat 2026", level: 1 })).toBeInTheDocument();
    expect(fake.requests.at(-1)?.body).toEqual({ name: "Shared flat 2026", description: null, expectedVersion: 1 });
    expect(screen.getByText("Changes saved.")).toBeInTheDocument();
  });

  it("shows the group as it is after a concurrent demotion", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    fake.respond("GET", GROUP, json(200, details()), json(200, details({ callerRole: "member" })));
    fake.respond("PUT", GROUP, problem(403, "group_admin_required"));
    renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText(/no longer an administrator/)).toBeInTheDocument();
    expect(await screen.findByLabelText("Your role: Member")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
  });

  it("shows not found for missing, malformed and inaccessible group IDs", async () => {
    const first = renderPage(<GroupViewPage />);
    expect(screen.getByRole("alert")).toHaveTextContent("Group not found");
    first.unmount();

    search = "id=not-a-group";
    const second = renderPage(<GroupViewPage />);
    expect(screen.getByRole("alert")).toHaveTextContent("Group not found");
    second.unmount();
    expect(fake.requests).toEqual([]);

    search = `id=${GROUP_ID}`;
    fake.respond("GET", GROUP, problem(404, "group_not_found"));
    renderPage(<GroupViewPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Group not found");
    expect(screen.getByRole("link", { name: "Back to groups" })).toHaveAttribute("href", "/app/groups");
  });
});

describe("GM-FT04 accessibility and keyboard operation", () => {
  it("has no structural axe violations on the list page", async () => {
    fake.respond("GET", LIST, json(200, { items: [summary()] }));
    const { container } = renderPage(<GroupsPage />);
    await screen.findByRole("list", { name: "Your groups" });
    await expectNoAxeViolations(container);
  });

  it("creates a group using only the keyboard, without axe violations", async () => {
    const user = userEvent.setup();
    fake.respond("POST", LIST, json(201, details()));
    const { container } = renderPage(<NewGroupPage />);
    await expectNoAxeViolations(container);

    await user.tab();
    expect(screen.getByRole("textbox", { name: /Name/ })).toHaveFocus();
    await user.keyboard("Flat share");
    await user.tab();
    expect(screen.getByRole("textbox", { name: /Description/ })).toHaveFocus();
    await user.keyboard("Rent");
    await user.tab();
    expect(screen.getByRole("button", { name: "Create group" })).toHaveFocus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(push).toHaveBeenCalled());
  });

  it("has no structural axe violations on the detail page while editing and in conflict", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    fake.respond("GET", GROUP, json(200, details()));
    fake.respond("PUT", GROUP, problem(409, "group_version_conflict"));
    const { container } = renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    await expectNoAxeViolations(container);
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await screen.findByRole("alert");
    await expectNoAxeViolations(container);
  });
});

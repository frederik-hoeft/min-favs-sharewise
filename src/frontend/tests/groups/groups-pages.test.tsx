import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";
import { createIdentityRuntime, type IdentityRuntime } from "@/core/identity";
import GroupsPage from "@/app/(app)/app/(standard)/groups/page";
import NewGroupPage from "@/app/(app)/app/(standard)/groups/new/page";
import OverviewPage from "@/app/(app)/app/groups/view/page";
import GroupViewLayout from "@/app/(app)/app/groups/view/layout";
import { useGroupWorkspace } from "@/app/(app)/app/groups/_components/group-workspace-context";
function GroupViewPage() { return <GroupViewLayout><OverviewPage /></GroupViewLayout>; }
import { authenticationResponse, SESSION_TWO } from "../identity/support";
import { TestProviders } from "../support/TestProviders";
import { GROUP_ID, deferred, details, json, problem, scriptedClient, summary, workspace } from "./support";

const LIST = "/api/groups/v1/groups";
const GROUP = `${LIST}/${GROUP_ID}`;
const WORKSPACE = `${GROUP}/workspace`;

let fake = scriptedClient();
// Pages obtain their client from the workspace guard's session facade, as in production; its transport is scripted.
let runtime: IdentityRuntime = createIdentityRuntime({ fetchImpl: fake.fetchImpl });
let search = "";
const push = vi.fn();
const replace = vi.fn();

vi.mock("next/navigation", async (importOriginal) => ({
  ...await importOriginal<typeof import("next/navigation")>(),
  usePathname: () => search ? "/app/groups/view" : "/app/groups",
  useRouter: () => ({ push, replace, back: vi.fn(), forward: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }),
  useSearchParams: () => new URLSearchParams(search),
}));

beforeEach(() => {
  fake = scriptedClient();
  runtime = createIdentityRuntime({ fetchImpl: fake.fetchImpl });
  runtime.session.accept(authenticationResponse("group-access-token"));
  search = "";
  push.mockReset();
  vi.spyOn(window, "matchMedia").mockImplementation(query => ({ matches: query.includes("min-width"), media: query, onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }));
  replace.mockReset();
});

afterEach(() => { runtime.dispose(); vi.restoreAllMocks(); });

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

/**
 * Submits a form twice within one React batch, so the second submission sees the same rendered
 * state as the first and only a guard independent of rendering can reject it.
 */
function submitTwiceBeforeRendering(form: HTMLFormElement) {
  act(() => {
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  });
}

function formOf(field: HTMLElement): HTMLFormElement {
  const form = field.closest("form");
  if (!form) throw new Error("field is not inside a form");
  return form;
}

/**
 * Runs every queued promise continuation, so a request or navigation that should not happen
 * would have happened. One macrotask turn drains the whole microtask queue; the scripted
 * transport involves no timers, so this does not depend on timing.
 */
async function settlePendingWork() {
  await act(async () => {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  });
}

async function expectNoAxeViolations(container: HTMLElement) {
  const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
  expect(results.violations).toEqual([]);
}

describe("GM-FT01 group list", () => {
  it("lists groups with links and member counts and searches descriptions", async () => {
    fake.respond("GET", LIST, json(200, { items: [
      summary(),
      summary({ id: "0193a5c4-7e1f-7c2a-9b3d-000000000002", name: "Ski trip", description: null, memberCount: 1, callerRole: "member" }),
    ] }));
    renderPage(<GroupsPage />);

    const list = await screen.findByRole("list", { name: "Your groups" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByRole("link", { name: "Flat share" })).toHaveAttribute("href", `/app/groups/view?id=${GROUP_ID}`);
    expect(within(items[0]).getByText("3 members")).toBeInTheDocument();
    expect(within(items[0]).getByRole("link", { name: "Flat share" })).toHaveAccessibleDescription("3 members");
    expect(within(items[1]).getByText("1 member")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create group" })).toHaveAttribute("href", "/app/groups/new");
    const user = userEvent.setup();
    const searchField = screen.getByRole("textbox", { name: "Search groups" });
    await user.type(searchField, "GROCERIES");
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    await user.clear(searchField);
    await user.type(searchField, "ski");
    expect(screen.getByRole("link", { name: "Ski trip" })).toBeInTheDocument();
    await user.type(searchField, " nonexistent");
    expect(screen.getByText("No groups match your search.")).toBeInTheDocument();
    expect(fake.requests).toHaveLength(1);
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

    const name = screen.getByRole("textbox", { name: /Name/ });
    expect(name).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "Create group" })).toBeEnabled();
    expect(screen.getByRole("link", { name: "Cancel" })).not.toHaveAttribute("aria-disabled");
    await user.type(name, "!");
    expect(name).toHaveValue("Flat share!");
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
    await settlePendingWork();
    expect(fake.requests).toHaveLength(1);
    expect(screen.getByRole("textbox", { name: /Name/ })).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "Create group" })).toBeEnabled();
  });

  it("sends one creation request and locks the form until the created group opens", async () => {
    const user = userEvent.setup();
    const response = deferred<Response>();
    fake.respond("POST", LIST, response.promise);
    renderPage(<NewGroupPage />);
    const name = screen.getByRole("textbox", { name: /Name/ });
    await user.type(name, "Flat share");
    await user.click(screen.getByRole("button", { name: "Create group" }));
    await waitFor(() => expect(fake.requests).toHaveLength(1));

    expect(formOf(name)).toHaveAttribute("aria-busy", "true");
    expect(name).toHaveAttribute("readonly");
    expect(screen.getByRole("textbox", { name: /Description/ })).toHaveAttribute("readonly");
    await user.type(name, " 2");
    expect(name).toHaveValue("Flat share");
    // Creation cannot be withdrawn, so Cancel does not pretend to.
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("aria-disabled", "true");
    await user.type(name, "{Enter}");
    submitTwiceBeforeRendering(formOf(name));
    await settlePendingWork();
    expect(fake.requests).toHaveLength(1);

    await act(async () => response.resolve(json(201, details())));
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/app/groups/view?id=${GROUP_ID}`));
    expect(name).toHaveAttribute("readonly");
    submitTwiceBeforeRendering(formOf(name));
    await settlePendingWork();
    expect(fake.requests).toHaveLength(1);
    expect(push).toHaveBeenCalledTimes(1);
  });

  it("rejects a repeated submission that arrives before the busy state renders", async () => {
    const user = userEvent.setup();
    const response = deferred<Response>();
    fake.respond("POST", LIST, response.promise);
    renderPage(<NewGroupPage />);
    const name = screen.getByRole("textbox", { name: /Name/ });
    await user.type(name, "Flat share");

    submitTwiceBeforeRendering(formOf(name));
    await settlePendingWork();
    expect(fake.requests).toHaveLength(1);
    await act(async () => response.resolve(json(201, details())));
    await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
  });

  it("does not navigate when the page was left before the creation response arrived", async () => {
    const user = userEvent.setup();
    const response = deferred<Response>();
    fake.respond("POST", LIST, response.promise);
    const view = renderPage(<NewGroupPage />);
    await user.type(screen.getByRole("textbox", { name: /Name/ }), "Flat share");
    await user.click(screen.getByRole("button", { name: "Create group" }));
    await waitFor(() => expect(fake.requests).toHaveLength(1));

    // Workspace navigation replaces the page while the POST is outstanding.
    view.unmount();
    await act(async () => response.resolve(json(201, details())));
    await settlePendingWork();
    expect(push).not.toHaveBeenCalled();
    expect(fake.requests).toHaveLength(1);
  });
});

describe("GM-FT03 group details", () => {
  it("loads the roster once, names the breadcrumb and opens an accessible mobile drawer", async () => {
    search = `id=${GROUP_ID}`;
    vi.mocked(window.matchMedia).mockImplementation(query => ({ matches: false, media: query, onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }));
    fake.respond("GET", WORKSPACE, json(200, workspace()));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    const toggle = await screen.findByRole("button", { name: "Group information" });
    expect(screen.getByRole("navigation", { name: "Breadcrumb" }).querySelector("[aria-current=page]")).toHaveTextContent("Flat share");
    expect(screen.queryByRole("list", { name: "Group members" })).not.toBeInTheDocument();
    await user.click(toggle);
    const drawer = await screen.findByRole("dialog", { name: "Group information" });
    expect(within(drawer).getByRole("list", { name: "Group members" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Group information" })).not.toBeInTheDocument());
    expect(fake.requests).toHaveLength(1);
  });

  it("provides the same workspace snapshot to a replaceable center view without an additional request", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, workspace()));
    function CenterView() {
      const { group, members } = useGroupWorkspace();
      return <p>{group.name}: {members.length} loaded memberships</p>;
    }
    renderPage(<GroupViewLayout><CenterView /></GroupViewLayout>);
    expect(await screen.findByText("Flat share: 3 loaded memberships")).toBeInTheDocument();
    expect(fake.requests).toHaveLength(1);
  });

  it("ignores an older group response after switching the query parameter", async () => {
    search = `id=${GROUP_ID}`;
    const stale = deferred<Response>();
    fake.respond("GET", WORKSPACE, stale.promise);
    const secondId = "0193a5c4-7e1f-7c2a-9b3d-000000000002";
    fake.respond("GET", `${LIST}/${secondId}/workspace`, json(200, workspace({ id: secondId, name: "Ski trip" })));
    const tree = () => <TestProviders><IdentityProvider runtime={runtime}><RequireAuthentication><GroupViewPage /></RequireAuthentication></IdentityProvider></TestProviders>;
    const view = render(tree());
    await waitFor(() => expect(fake.requests).toHaveLength(1));
    search = `id=${secondId}`;
    view.rerender(tree());
    await screen.findByRole("heading", { name: "Ski trip", level: 1 });
    await act(async () => stale.resolve(json(200, workspace())));
    expect(screen.queryByRole("heading", { name: "Flat share", level: 1 })).not.toBeInTheDocument();
  });

  it("offers editing only to administrators", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, workspace({ callerRole: "member" })));
    const first = renderPage(<GroupViewPage />);
    expect(await screen.findByRole("heading", { name: "Flat share", level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("heading", {name: "Members (3)"})).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
    first.unmount();

    fake.respond("GET", WORKSPACE, json(200, workspace()));
    renderPage(<GroupViewPage />);
    expect(await screen.findByRole("button", { name: "Edit details" })).toBeInTheDocument();
  });

  it("keeps input on a 409 until the user loads the current metadata, then saves against it", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE,
      json(200, workspace()),
      json(200, workspace({ name: "Shared flat", description: null, version: 1 })),
      json(200, workspace({ name: "Shared flat 2026", description: null, version: 2 })));
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
    expect(screen.getByRole("heading", { name: "Shared flat", level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Name/ })).not.toHaveAttribute("readonly");

    await user.clear(screen.getByRole("textbox", { name: /Name/ }));
    await user.type(screen.getByRole("textbox", { name: /Name/ }), "Shared flat 2026");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { name: "Shared flat 2026", level: 1 })).toBeInTheDocument();
    expect(fake.requests.filter(request => request.method === "PUT").at(-1)?.body).toEqual({ name: "Shared flat 2026", description: null, expectedVersion: 1 });
    expect(screen.getByText("Changes saved.")).toBeInTheDocument();
  });

  it("ignores a conflict reload abandoned by Cancel, so a reopened editor still saves against its own version", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    const current = deferred<Response>();
    fake.respond("GET", WORKSPACE, json(200, workspace()), current.promise);
    fake.respond("PUT", GROUP, problem(409, "group_version_conflict"), problem(409, "group_version_conflict"));
    renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    await user.clear(screen.getByRole("textbox", { name: /Name/ }));
    await user.type(screen.getByRole("textbox", { name: /Name/ }), "My edit");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await user.click(within(await screen.findByRole("alert")).getByRole("button", { name: "Load current details" }));
    await waitFor(() => expect(fake.requests.filter((request) => request.method === "GET")).toHaveLength(2));

    // While the current details load, the draft and saving are locked, but leaving is not.
    const name = screen.getByRole("textbox", { name: /Name/ });
    expect(name).toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
    submitTwiceBeforeRendering(formOf(name));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "Edit details" }));
    expect(screen.getByRole("textbox", { name: /Name/ })).toHaveValue("Flat share");

    // Another administrator's version 1 arrives for the editor that no longer exists.
    await act(async () => current.resolve(json(200, workspace({ name: "Shared flat", description: null, version: 1 }))));
    await settlePendingWork();
    expect(screen.getByRole("heading", { name: "Flat share", level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Name/ })).toHaveValue("Flat share");

    await user.clear(screen.getByRole("textbox", { name: /Name/ }));
    await user.type(screen.getByRole("textbox", { name: /Name/ }), "Stale edit");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This group was changed in the meantime");
    const puts = fake.requests.filter((request) => request.method === "PUT");
    expect(puts.map((request) => request.body)).toEqual([
      { name: "My edit", description: "Rent and groceries", expectedVersion: 0 },
      { name: "Stale edit", description: "Rent and groceries", expectedVersion: 0 },
    ]);
  });

  it("locks the draft, Cancel and repeated saves while a save is pending", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    const saved = deferred<Response>();
    fake.respond("GET", WORKSPACE, json(200, workspace()), json(200, workspace({ name: "Shared flat", version: 1 })));
    fake.respond("PUT", GROUP, saved.promise);
    renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    const name = screen.getByRole("textbox", { name: /Name/ });
    await user.clear(name);
    await user.type(name, "Shared flat");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(fake.requests.filter((request) => request.method === "PUT")).toHaveLength(1));

    expect(formOf(name)).toHaveAttribute("aria-busy", "true");
    expect(name).toHaveAttribute("readonly");
    await user.type(name, " 2026");
    expect(name).toHaveValue("Shared flat");
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    await user.type(name, "{Enter}");
    submitTwiceBeforeRendering(formOf(name));
    await settlePendingWork();
    expect(fake.requests.filter((request) => request.method === "PUT")).toHaveLength(1);

    await act(async () => saved.resolve(json(200, details({ name: "Shared flat", version: 1 }))));
    expect(await screen.findByRole("heading", { name: "Shared flat", level: 1 })).toBeInTheDocument();
    expect(screen.getByText("Changes saved.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Name/ })).not.toBeInTheDocument();
  });

  it("keeps the draft and its version editable after a failed save", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, workspace()), json(200, workspace({ name: "Shared flat", version: 1 })));
    fake.respond("PUT", GROUP, new TypeError("offline"), json(200, details({ name: "Shared flat", version: 1 })));
    renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    const name = screen.getByRole("textbox", { name: /Name/ });
    await user.clear(name);
    await user.type(name, "Shared");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The save outcome could not be confirmed");

    expect(name).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
    await user.type(name, " flat");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("heading", { name: "Shared flat", level: 1 })).toBeInTheDocument();
    expect(fake.requests.filter((request) => request.method === "PUT").map((request) => request.body)).toEqual([
      { name: "Shared", description: "Rent and groceries", expectedVersion: 0 },
      { name: "Shared flat", description: "Rent and groceries", expectedVersion: 0 },
    ]);
  });

  it("shows the group as it is after a concurrent demotion", async () => {
    const user = userEvent.setup();
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, workspace()), json(200, workspace({ callerRole: "member" })));
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
    fake.respond("GET", WORKSPACE, problem(404, "group_not_found"));
    renderPage(<GroupViewPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Group not found");
    expect(screen.getByRole("link", { name: "Back to groups" })).toHaveAttribute("href", "/app/groups");
  });
});

describe("GM-FT05 membership administration", () => {
  const initial = workspace();
  const caller = initial.members[0];
  const target = initial.members[1];
  const targetPath = `${GROUP}/members/${target.id}`;
  const callerPath = `${GROUP}/members/${caller.id}`;
  const noContent = () => new Response(null, { status: 204 });
  const openMenu = async (user: ReturnType<typeof userEvent.setup>, member = target) => {
    await user.click(await screen.findByRole("button", { name: member.id === caller.id ? "Manage your membership" : `Manage member ${member.userId}` }));
  };
  const memberRow = (userId: string) => within(screen.getByRole("list", { name: "Group members" })).getAllByRole("listitem").find(row => row.textContent?.includes(userId))!;

  it("promotes by membership identity and reloads the roster instead of inferring a new role", async () => {
    search = `id=${GROUP_ID}`;
    const promoted = { ...initial, members: initial.members.map(member => member.id === target.id ? { ...member, role: "admin" as const } : member) };
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, promoted));
    fake.respond("PUT", `${targetPath}/role`, noContent());
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Make administrator" }));
    const dialog = screen.getByRole("dialog", { name: "Make administrator?" });
    expect(within(dialog).getByText(target.userId)).toBeInTheDocument();
    expect(fake.requests).toHaveLength(1);
    await user.click(within(dialog).getByRole("button", { name: "Make administrator" }));
    await screen.findByText("Membership updated.");
    expect(memberRow(target.userId)).toHaveTextContent("Administrator");
    expect(fake.requests).toEqual([
      { method: "GET", path: WORKSPACE, body: undefined },
      { method: "PUT", path: `${targetPath}/role`, body: { role: "admin" } },
      { method: "GET", path: WORKSPACE, body: undefined },
    ]);
  });

  it("transfers administration atomically, then removes administrative affordances", async () => {
    search = `id=${GROUP_ID}`;
    const transferred = { ...initial, group: { ...initial.group, callerRole: "member" as const }, members: initial.members.map(member => ({ ...member, role: member.id === target.id ? "admin" as const : "member" as const })) };
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, transferred));
    fake.respond("POST", `${targetPath}/transfer-administration`, noContent());
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Transfer administration" }));
    const dialog = screen.getByRole("dialog", { name: "Transfer administration?" });
    expect(dialog).toHaveTextContent("you become a regular member in the same operation");
    await user.click(within(dialog).getByRole("button", { name: "Transfer administration" }));
    await screen.findByLabelText("Your role: Member");
    expect(screen.queryByRole("button", { name: /^Manage member/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
    expect(memberRow(target.userId)).toHaveTextContent("Administrator");
  });

  it("blocks leaving or self-demotion as the final administrator, while permitting transfer", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, initial));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    expect(await screen.findByRole("button", { name: "Leave group" })).toBeDisabled();
    await openMenu(user, caller);
    expect(screen.getByRole("menuitem", { name: "Make member" })).toBeDisabled();
    await user.keyboard("{Escape}");
    await openMenu(user);
    expect(screen.getByRole("menuitem", { name: "Transfer administration" })).toBeEnabled();
  });

  it("requires confirmation of self-demotion and handles a concurrent final-admin conflict", async () => {
    search = `id=${GROUP_ID}`;
    const twoAdmins = { ...initial, members: initial.members.map(member => member.id === target.id ? { ...member, role: "admin" as const } : member) };
    fake.respond("GET", WORKSPACE, json(200, twoAdmins), json(200, initial));
    fake.respond("PUT", `${callerPath}/role`, problem(409, "group_last_administrator"));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user, caller);
    await user.click(screen.getByRole("menuitem", { name: "Make member" }));
    const dialog = screen.getByRole("dialog", { name: "Give up administration?" });
    await user.click(within(dialog).getByRole("button", { name: "Make member" }));
    expect(await screen.findByText("A group must retain at least one administrator.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Leave group" })).toBeDisabled();
  });

  it("reconciles a stale removal target without treating the whole group as missing", async () => {
    search = `id=${GROUP_ID}`;
    const updated = { ...initial, group: { ...initial.group, memberCount: 2 }, members: initial.members.filter(member => member.id !== target.id) };
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, updated));
    fake.respond("DELETE", targetPath, problem(404, "group_member_not_found"));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Remove member" }));
    await user.click(within(screen.getByRole("dialog", { name: "Remove member?" })).getByRole("button", { name: "Remove member" }));
    expect(await screen.findByText(/That membership is no longer available/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Members (2)" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: `Manage member ${target.userId}` })).not.toBeInTheDocument();
  });

  it("reconciles concurrent demotion instead of keeping stale administrator controls", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, workspace({ callerRole: "member" })));
    fake.respond("PUT", `${targetPath}/role`, problem(403, "group_admin_required"));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Make administrator" }));
    await user.click(within(screen.getByRole("dialog", { name: "Make administrator?" })).getByRole("button", { name: "Make administrator" }));
    await screen.findByLabelText("Your role: Member");
    expect(screen.queryByRole("button", { name: /^Manage member/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
  });

  it("does not resubmit an uncertain removal and keeps the confirmation locked until it settles", async () => {
    search = `id=${GROUP_ID}`;
    const response = deferred<Response | Error>();
    const updated = { ...initial, group: { ...initial.group, memberCount: 2 }, members: initial.members.filter(member => member.id !== target.id) };
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, updated));
    fake.respond("DELETE", targetPath, response.promise);
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Remove member" }));
    const dialog = screen.getByRole("dialog", { name: "Remove member?" });
    const submit = within(dialog).getByRole("button", { name: "Remove member" });
    act(() => { submit.click(); submit.click(); });
    await waitFor(() => expect(fake.requests.filter(request => request.method === "DELETE")).toHaveLength(1));
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(dialog).toBeInTheDocument();
    await act(async () => response.resolve(new TypeError("connection lost")));
    expect(await screen.findByText(/outcome of the membership change could not be confirmed/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Members (2)" })).toBeInTheDocument();
    expect(fake.requests.filter(request => request.method === "DELETE")).toHaveLength(1);
  });

  it("lets ordinary members cancel and confirm leaving, clearing private data before navigation", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, workspace({ callerRole: "member" })));
    fake.respond("DELETE", callerPath, noContent());
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    const leave = await screen.findByRole("button", { name: "Leave group" });
    expect(screen.queryByRole("button", { name: /^Manage member/ })).not.toBeInTheDocument();
    await user.click(leave);
    const dialog = screen.getByRole("dialog", { name: "Leave group?" });
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(leave).toHaveFocus();
    expect(fake.requests).toHaveLength(1);
    await user.click(leave);
    await user.click(within(screen.getByRole("dialog", { name: "Leave group?" })).getByRole("button", { name: "Leave group" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/app/groups"));
    expect(screen.queryByRole("list", { name: "Group members" })).not.toBeInTheDocument();
    expect(fake.requests).toHaveLength(2);
  });

  it("ignores a delayed leave acknowledgment belonging to an abandoned group", async () => {
    search = `id=${GROUP_ID}`;
    const response = deferred<Response>();
    fake.respond("GET", WORKSPACE, json(200, workspace({ callerRole: "member" })));
    fake.respond("DELETE", callerPath, response.promise);
    const nextId = "0193a5c4-7e1f-7c2a-9b3d-000000000002";
    fake.respond("GET", `${LIST}/${nextId}/workspace`, json(200, workspace({ id: nextId, name: "Another group" })));
    const tree = () => <TestProviders><IdentityProvider runtime={runtime}><RequireAuthentication><GroupViewPage /></RequireAuthentication></IdentityProvider></TestProviders>;
    const view = render(tree());
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Leave group" }));
    await user.click(within(screen.getByRole("dialog", { name: "Leave group?" })).getByRole("button", { name: "Leave group" }));
    await waitFor(() => expect(fake.requests.filter(request => request.method === "DELETE")).toHaveLength(1));
    search = `id=${nextId}`;
    view.rerender(tree());
    await screen.findByRole("heading", { name: "Another group", level: 1 });
    await act(async () => response.resolve(noContent()));
    expect(replace).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Another group", level: 1 })).toBeInTheDocument();
  });

  it("does not reconcile an old session's submitted mutation into a replacement session", async () => {
    search = `id=${GROUP_ID}`;
    const response = deferred<Response>();
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, workspace({ callerRole: "member" })));
    fake.respond("DELETE", targetPath, response.promise);
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Remove member" }));
    await user.click(within(screen.getByRole("dialog", { name: "Remove member?" })).getByRole("button", { name: "Remove member" }));
    await waitFor(() => expect(fake.requests.filter(request => request.method === "DELETE")).toHaveLength(1));
    await act(async () => runtime.session.accept(authenticationResponse("replacement", undefined, SESSION_TWO)));
    await screen.findByLabelText("Your role: Member");
    await act(async () => response.resolve(noContent()));
    await settlePendingWork();
    expect(screen.queryByText("Membership updated.")).not.toBeInTheDocument();
    expect(fake.requests.filter(request => request.method === "GET")).toHaveLength(2);
    expect(replace).not.toHaveBeenCalled();
  });

  it("prevents membership actions while an independently versioned metadata draft is open", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, initial));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    expect(screen.getByRole("button", { name: `Manage member ${target.userId}` })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Leave group" })).toBeDisabled();
    const draft = screen.getByRole("textbox", { name: /Name/ });
    await user.type(draft, " draft");
    await act(async () => runtime.session.accept(authenticationResponse("renewed")));
    expect(draft).toHaveValue("Flat share draft");
    expect(fake.requests).toHaveLength(1);
  });

  it("preserves an open confirmation during bearer renewal but disposes it on session replacement", async () => {
    search = `id=${GROUP_ID}`;
    fake.respond("GET", WORKSPACE, json(200, initial), json(200, workspace({ callerRole: "member" })));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Remove member" }));
    const dialog = screen.getByRole("dialog", { name: "Remove member?" });
    await act(async () => runtime.session.accept(authenticationResponse("renewed")));
    expect(screen.getByRole("dialog", { name: "Remove member?" })).toBe(dialog);
    expect(fake.requests).toHaveLength(1);
    await act(async () => runtime.session.accept(authenticationResponse("replacement", undefined, SESSION_TWO)));
    await screen.findByLabelText("Your role: Member");
    expect(dialog).not.toBeInTheDocument();
    expect(fake.requests.every(request => request.method === "GET")).toBe(true);
  });

  it("closes the mobile Drawer before confirmation and has accessible controls", async () => {
    search = `id=${GROUP_ID}`;
    vi.mocked(window.matchMedia).mockImplementation(query => ({ matches: false, media: query, onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }));
    fake.respond("GET", WORKSPACE, json(200, initial));
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await user.click(await screen.findByRole("button", { name: "Group information" }));
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Remove member" }));
    expect(screen.queryByRole("dialog", { name: "Group information" })).not.toBeInTheDocument();
    const dialog = screen.getByRole("dialog", { name: "Remove member?" });
    await expectNoAxeViolations(dialog);
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Group information" })).toHaveFocus();
    expect(fake.requests).toHaveLength(1);
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
    fake.respond("GET", WORKSPACE, json(200, workspace()));
    fake.respond("PUT", GROUP, problem(409, "group_version_conflict"));
    const { container } = renderPage(<GroupViewPage />);

    await user.click(await screen.findByRole("button", { name: "Edit details" }));
    await expectNoAxeViolations(container);
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    await screen.findByRole("alert");
    await expectNoAxeViolations(container);
  });
});

const INVITATIONS = `${GROUP}/invitations`;
const INVITATION_ID = "0193a5c4-7e1f-7c2a-9b3d-000000000111";
const INVITATION_TOKEN = `${INVITATION_ID}.${"A".repeat(43)}`;
const invitation = { id: INVITATION_ID, createdAt: "2026-11-25T12:00:00Z", expiresAt: null, maxUses: 1, useCount: 0 };

async function openInvitations() {
  search = `?id=${GROUP_ID}`;
  fake.respond("GET", WORKSPACE, json(200, workspace()));
  const user = userEvent.setup();
  renderPage(<GroupViewPage />);
  await user.click(await screen.findByRole("button", { name: "Manage invitation links" }));
  return user;
}

describe("GI-FT16 invitation management", () => {
  it("discloses a link only on creation, supports manual copy, and revokes with confirmation", async () => {
    fake.respond("GET", INVITATIONS, json(200, { items: [] }), json(200, { items: [invitation] }), json(200, { items: [] }));
    fake.respond("POST", INVITATIONS, json(201, { invitation, token: INVITATION_TOKEN }));
    fake.respond("DELETE", `${INVITATIONS}/${INVITATION_ID}`, new Response(null, { status: 204 }));
    const user = await openInvitations();
    await screen.findByText("No invitation links yet.");
    expect(screen.queryByLabelText("Invitation link")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByLabelText("Invitation link")).toHaveValue(`${window.location.origin}/invite#${INVITATION_TOKEN}`);
    expect(fake.requests.find(r => r.method === "POST")?.body).toMatchObject({ maxUses: 1, expiresAt: expect.any(String) });
    vi.spyOn(navigator.clipboard, "writeText").mockRejectedValueOnce(new Error("not permitted"));
    await user.click(screen.getByRole("button", { name: "Copy link" }));
    expect(await screen.findByText(/select and copy the link manually/i)).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Close link" }));
    expect(screen.queryByLabelText("Invitation link")).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Revoke link" }));
    const dialog = screen.getByRole("dialog", { name: "Revoke invitation?" });
    expect(fake.requests.filter(r => r.method === "DELETE")).toHaveLength(0);
    await user.click(within(dialog).getByRole("button", { name: "Revoke link" }));
    expect(await screen.findByText("No invitation links yet.")).toBeVisible();
    expect(fake.requests.filter(r => r.method === "DELETE")).toHaveLength(1);
  });

  it("reconciles uncertain creation without replaying and does not reveal a lost secret", async () => {
    fake.respond("GET", INVITATIONS, json(200, { items: [] }), json(200, { items: [invitation] }));
    fake.respond("POST", INVITATIONS, new Error("connection lost"));
    const user = await openInvitations();
    await screen.findByText("No invitation links yet.");
    await user.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByText(/invitation change could not be confirmed/i)).toBeVisible();
    expect(await screen.findByText(INVITATION_ID)).toBeVisible();
    expect(screen.queryByLabelText("Invitation link")).not.toBeInTheDocument();
    expect(fake.requests.filter(r => r.method === "POST")).toHaveLength(1);
  });

  it("returns to a reconciled workspace when administration is lost", async () => {
    fake.respond("GET", INVITATIONS, problem(403, "group_admin_required"));
    fake.respond("GET", WORKSPACE, json(200, workspace()), json(200, workspace({ callerRole: "member" })));
    search = `?id=${GROUP_ID}`;
    const user = userEvent.setup();
    renderPage(<GroupViewPage />);
    await user.click(await screen.findByRole("button", { name: "Manage invitation links" }));
    expect(await screen.findByText("You are no longer an administrator of this group.")).toBeVisible();
    await screen.findByText("Your group workspace");
    expect(screen.queryByRole("button", { name: "Manage invitation links" })).not.toBeInTheDocument();
  });

  it("shows expired and exhausted links, supports custom constraints, and is structurally accessible", async () => {
    const expired = { ...invitation, expiresAt: "2000-01-01T00:00:00Z" };
    const exhausted = { ...invitation, id: `${INVITATION_ID.slice(0, -1)}2`, useCount: 1 };
    fake.respond("GET", INVITATIONS, json(200, { items: [expired, exhausted] }), json(200, { items: [expired, exhausted, invitation] }));
    fake.respond("POST", INVITATIONS, json(201, { invitation, token: INVITATION_TOKEN }));
    const user = await openInvitations();
    expect(await screen.findByText("Expired")).toBeVisible();
    expect(screen.getByText("Exhausted")).toBeVisible();
    await user.selectOptions(screen.getByLabelText("Expiration"), "never");
    await user.selectOptions(screen.getByLabelText("Maximum uses"), "finite");
    const limit = screen.getByLabelText("Usage limit");
    await user.clear(limit);
    await user.type(limit, "5");
    await expectNoAxeViolations(document.body);
    await user.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByLabelText("Invitation link")).toBeVisible();
    expect(fake.requests.find(request => request.method === "POST")?.body).toEqual({ maxUses: 5, expiresAt: null });
    await expectNoAxeViolations(document.body);
  });

});

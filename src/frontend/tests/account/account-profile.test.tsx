import { apiTarget } from "../support/api-target";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import AccountPage from "@/app/(app)/app/(standard)/account/page";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";
import { createIdentityRuntime } from "@/core/identity";
import type { AccountProfile } from "@/core/identity";
import { TestProviders } from "../support/TestProviders";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/app/account",
}));
import { ControlledLocks, SESSION_TWO, authenticationResponse, deferred, jsonResponse, problemResponse } from "../identity/support";

const initial: AccountProfile = { userId: "00000000-0000-0000-0000-000000000001", email: "alice@example.com", displayName: "Alice" };
const active: Array<ReturnType<typeof createIdentityRuntime>> = [];
function renderAccount(fetchImpl: typeof fetch = vi.fn(async () => jsonResponse(initial)) as typeof fetch, signedIn = true) {
  const runtime = createIdentityRuntime({ fetchImpl, locks: () => new ControlledLocks().manager });
  active.push(runtime);
  if (signedIn) runtime.session.accept(authenticationResponse("first-bearer"));
  const result = render(
    <TestProviders><IdentityProvider runtime={runtime}><RequireAuthentication><AccountPage /></RequireAuthentication></IdentityProvider></TestProviders>,
  );
  return { runtime, fetchImpl, ...result };
}
afterEach(() => { replace.mockReset(); for (const runtime of active.splice(0)) runtime.dispose(); });

async function loadedForm() {
  return screen.findByRole("form", { name: "Account profile" });
}

function nameInput() {
  return screen.getByRole("textbox", { name: /^Display name/i });
}

describe("Account profile page", () => {
  it("redirects when an established session is rejected, without exposing account details", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(initial));
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    await loadedForm();
    await act(async () => runtime.session.invalidate("expired"));
    expect(screen.queryByDisplayValue(initial.email)).not.toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Faccount"));
    expect(screen.queryByText("Sign in required")).not.toBeInTheDocument();
  });

  it("loads a required profile, keeps email read-only, and saves Unicode without trimming or HTML interpretation", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      expect(apiTarget(path)).toBe("/api/iam/v1/account");
      return jsonResponse(init?.method === "PATCH" ? { ...initial, displayName: JSON.parse(String(init.body)).displayName } : initial);
    });
    renderAccount(fetchImpl as typeof fetch);
    expect(screen.getByRole("status", { name: "Loading account" })).toBeInTheDocument();
    const form = await loadedForm();
    expect(within(form).getByRole("textbox", { name: "Email address" })).toHaveAttribute("readonly");
    expect(within(form).getByDisplayValue(initial.email)).toBeInTheDocument();
    expect(nameInput()).toHaveValue("Alice");
    expect(nameInput()).toBeRequired();
    // HTML measures UTF-16 units, so its upper bound is twice the API's scalar limit.
    expect(nameInput()).toHaveAttribute("maxlength", "160");
    expect(screen.getByRole("button", { name: "Update display name" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Clear display name" })).not.toBeInTheDocument();

    const user = userEvent.setup();
    const text = "  <img src=x onerror=alert(1)> 𐐀  ";
    await user.clear(nameInput());
    await user.type(nameInput(), text);
    await user.click(screen.getByRole("button", { name: "Update display name" }));
    await screen.findByText("Display name updated.");
    expect(nameInput()).toHaveValue(text);
    expect(document.querySelector("img[src='x']")).toBeNull();
    const patch = fetchImpl.mock.calls.find(([, options]) => options?.method === "PATCH");
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ displayName: text });
  });

  it.each(["", "  \t  "])("rejects clearing a required display name %j", async (blank) => {
    const fetchImpl = vi.fn(async () => jsonResponse(initial));
    renderAccount(fetchImpl as typeof fetch);
    await loadedForm();
    fireEvent.change(nameInput(), { target: { value: blank } });
    fireEvent.submit(screen.getByRole("form", { name: "Account profile" }));
    expect(await screen.findByText(/Enter a name containing non-whitespace text/)).toBeInTheDocument();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it.each(["A\u0001B", "A\u2028", "\ud800"])(
    "rejects invalid nonblank display name %j before sending a mutation", async (invalid) => {
      const fetchImpl = vi.fn(async () => jsonResponse({ ...initial, displayName: "Alice" }));
      renderAccount(fetchImpl as typeof fetch);
      await screen.findByDisplayValue("Alice");
      fireEvent.change(nameInput(), { target: { value: invalid } });
      fireEvent.click(screen.getByRole("button", { name: "Update display name" }));
      expect(await screen.findByText(/Enter a name containing non-whitespace text/)).toBeInTheDocument();
      expect(nameInput()).toHaveFocus();
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    },
  );

  it("accepts 80 astral Unicode characters and rejects a pasted 81st scalar", async () => {
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) =>
      jsonResponse(init?.method === "PATCH" ? { ...initial, displayName: JSON.parse(String(init.body)).displayName } : initial));
    renderAccount(fetchImpl as typeof fetch);
    await loadedForm();
    const exactLimit = "𐐀".repeat(80);
    fireEvent.change(nameInput(), { target: { value: exactLimit } });
    expect(nameInput()).toHaveValue(exactLimit);
    expect(screen.getByRole("button", { name: "Update display name" })).toBeEnabled();
    fireEvent.change(nameInput(), { target: { value: exactLimit + "A" } });
    expect(nameInput()).toHaveValue(exactLimit);
    expect(await screen.findByText("Enter at most 80 characters.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Update display name" })).toBeDisabled();
    fireEvent.change(nameInput(), { target: { value: exactLimit.slice(0, -2) } });
    expect(screen.queryByText("Enter at most 80 characters.")).not.toBeInTheDocument();
    fireEvent.change(nameInput(), { target: { value: exactLimit } });
    fireEvent.click(screen.getByRole("button", { name: "Update display name" }));
    await screen.findByText("Display name updated.");
    const patch = fetchImpl.mock.calls.find(([, options]) => options?.method === "PATCH");
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ displayName: exactLimit });
  });

  it("rejects overlong ASCII paste without mutating the accepted entry", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(initial));
    renderAccount(fetchImpl as typeof fetch);
    await loadedForm();
    fireEvent.change(nameInput(), { target: { value: "Bob" } });
    fireEvent.change(nameInput(), { target: { value: "A".repeat(81) } });
    expect(nameInput()).toHaveValue("Bob");
    expect(await screen.findByText("Enter at most 80 characters.")).toBeInTheDocument();
    fireEvent.submit(screen.getByRole("form", { name: "Account profile" }));
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("surfaces backend field errors by code and keeps local text after a rejected update", async () => {
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) => init?.method === "PATCH"
      ? problemResponse(400, "validation_failed", [{ field: "displayName", code: "invalid", message: "Not an allowed name." }])
      : jsonResponse({ ...initial, displayName: "Alice" }));
    renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    const user = userEvent.setup();
    await user.clear(nameInput());
    await user.type(nameInput(), "Bob");
    await user.click(screen.getByRole("button", { name: "Update display name" }));
    expect(await screen.findByText("Not an allowed name.")).toBeInTheDocument();
    expect(nameInput()).toHaveValue("Bob");
    expect(screen.getByText("Check your display name.")).toBeInTheDocument();
  });

  it("disables actions on pending writes and rejects repeated submissions", async () => {
    const pending = deferred<Response>();
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) =>
      init?.method === "PATCH" ? pending.promise : jsonResponse({ ...initial, displayName: "Alice" }));
    renderAccount(fetchImpl as typeof fetch);
    const form = await loadedForm();
    fireEvent.change(nameInput(), { target: { value: "Bob" } });
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(nameInput()).toBeDisabled();
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("button", { name: "Update display name" })).toBeDisabled();
    await act(async () => pending.resolve(jsonResponse({ ...initial, displayName: "Bob" })));
    await screen.findByText("Display name updated.");
    expect(nameInput()).toHaveValue("Bob");
  });

  it("requires reloading after an uncertain update instead of risking a blind duplicate", async () => {
    let gets = 0;
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PATCH") return new Response('{"displayName":', { status: 200, headers: { "content-type": "application/json" } });
      gets++;
      return jsonResponse({ ...initial, displayName: gets === 1 ? "Alice" : "Bob" });
    });
    renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    fireEvent.change(nameInput(), { target: { value: "Bob" } });
    fireEvent.click(screen.getByRole("button", { name: "Update display name" }));
    expect(await screen.findByText(/could not confirm the update/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Update display name" })).toBeDisabled();
    expect(nameInput()).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Reload account" }));
    await screen.findByDisplayValue("Bob");
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("handles unavailable accounts and retries failed loads without exposing previous details", async () => {
    let count = 0;
    const fetchImpl = vi.fn(async () => ++count === 1 ? problemResponse(500, "internal_error") : jsonResponse(initial));
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    expect(await screen.findByText(/Unable to load your account right now/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry loading" }));
    await loadedForm();
    expect(screen.getByDisplayValue(initial.email)).toBeInTheDocument();
    await act(async () => runtime.session.accept(authenticationResponse("other-bearer", undefined, SESSION_TWO)));
    await loadedForm();
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("shows account_unavailable as a noneditable 403 state", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(403, "account_unavailable"));
    renderAccount(fetchImpl as typeof fetch);
    expect(await screen.findByText(/This account is unavailable/)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Account profile" })).not.toBeInTheDocument();
  });

  it("keeps unsaved profile edits through a transparent same-session renewal", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      const url = apiTarget(path);
      if (url.endsWith("/account")) return jsonResponse({ ...initial, displayName: "Alice" });
      if (url.endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (url.endsWith("/refresh")) return jsonResponse(authenticationResponse("renewed"));
      return (init?.headers as Headers).get("Authorization") === "Bearer first-bearer"
        ? problemResponse(401, "invalid_credential") : jsonResponse({ ok: true });
    });
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    fireEvent.change(nameInput(), { target: { value: "Unsaved draft" } });
    await act(async () => { await runtime.api.get("/groups/v1/groups"); });
    expect(runtime.session.getAccessToken()).toBe("renewed");
    expect(nameInput()).toHaveValue("Unsaved draft");
    expect(screen.getByRole("button", { name: "Update display name" })).toBeEnabled();
    expect(fetchImpl.mock.calls.filter(([path]) => apiTarget(path).endsWith("/account"))).toHaveLength(1);
  });

  it("keeps the mutation pending across renewal and applies the successful replay without reloading", async () => {
    const refresh = deferred<Response>();
    const reply = deferred<Response>();
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      const url = apiTarget(path);
      if (url.endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (url.endsWith("/refresh")) return refresh.promise;
      if (init?.method === "PATCH") {
        expect(JSON.parse(String(init.body))).toEqual({ displayName: "Edited" });
        return (init.headers as Headers).get("Authorization") === "Bearer first-bearer"
          ? problemResponse(401, "invalid_credential") : reply.promise;
      }
      return jsonResponse({ ...initial, displayName: "Alice" });
    });
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    fireEvent.change(nameInput(), { target: { value: "Edited" } });
    fireEvent.submit(screen.getByRole("form", { name: "Account profile" }));
    await waitFor(() => expect(fetchImpl.mock.calls.some(([path]) => apiTarget(path).endsWith("/refresh"))).toBe(true));
    expect(nameInput()).toBeDisabled();
    expect(nameInput()).toHaveValue("Edited");
    await act(async () => refresh.resolve(jsonResponse(authenticationResponse("renewed"))));
    await waitFor(() => expect(fetchImpl.mock.calls.filter(([, init]) => init?.method === "PATCH")).toHaveLength(2));
    expect(runtime.session.getAccessToken()).toBe("renewed");
    expect(nameInput()).toBeDisabled();
    expect(nameInput()).toHaveValue("Edited");
    fireEvent.submit(screen.getByRole("form", { name: "Account profile" }));
    expect(fetchImpl.mock.calls.filter(([, init]) => init?.method === "PATCH")).toHaveLength(2);
    await act(async () => reply.resolve(jsonResponse({ ...initial, displayName: "Edited" })));
    expect(await screen.findByText("Display name updated.")).toBeInTheDocument();
    expect(nameInput()).toHaveValue("Edited");
    expect(nameInput()).toBeEnabled();
    expect(screen.getByRole("button", { name: "Update display name" })).toBeDisabled();
    expect(fetchImpl.mock.calls.filter(([path, init]) => apiTarget(path).endsWith("/account") && init?.method === "GET")).toHaveLength(1);
  });

  it("never displays a previous account while reloading for a new authentication context", async () => {
    const first = deferred<Response>();
    const second = deferred<Response>();
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) =>
      (init?.headers as Headers).get("Authorization") === "Bearer first-bearer" ? first.promise : second.promise);
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    await act(async () => runtime.session.accept(authenticationResponse("second-bearer", undefined, SESSION_TWO)));
    expect(screen.getByRole("status", { name: "Loading account" })).toBeInTheDocument();
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    await act(async () => first.resolve(jsonResponse(initial)));
    expect(screen.queryByDisplayValue("alice@example.com")).not.toBeInTheDocument();
    const nextProfile = { ...initial, userId: "00000000-0000-0000-0000-000000000002", email: "bob@example.com", displayName: "Bob" };
    await act(async () => second.resolve(jsonResponse(nextProfile)));
    expect(await screen.findByDisplayValue("bob@example.com")).toBeInTheDocument();
    expect(screen.queryByDisplayValue("alice@example.com")).not.toBeInTheDocument();
  });

  it("discards a stale successful PATCH response when the authenticated session changes", async () => {
    const pending = deferred<Response>();
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) => {
      const bearer = (init?.headers as Headers).get("Authorization");
      if (init?.method === "PATCH") return pending.promise;
      if (bearer === "Bearer second-bearer") return jsonResponse({ ...initial, email: "bob@example.com", displayName: "Bob" });
      return jsonResponse({ ...initial, displayName: "Alice" });
    });
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    fireEvent.change(nameInput(), { target: { value: "X" } });
    fireEvent.click(screen.getByRole("button", { name: "Update display name" }));
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    await act(async () => runtime.session.accept(authenticationResponse("second-bearer", undefined, SESSION_TWO)));
    await screen.findByDisplayValue("bob@example.com");
    await act(async () => pending.resolve(jsonResponse({ ...initial, displayName: "X" })));
    expect(nameInput()).toHaveValue("Bob");
    expect(screen.getByDisplayValue("bob@example.com")).toBeInTheDocument();
    expect(screen.queryByText("Display name updated.")).not.toBeInTheDocument();
  });

  it("has no detectable structural accessibility violations in the loaded profile view", async () => {
    const { container } = renderAccount();
    await loadedForm();
    const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
    expect(results.violations).toEqual([]);
  });
});

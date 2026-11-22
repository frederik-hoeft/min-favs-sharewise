import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import AccountPage from "@/app/(app)/app/account/page";
import { createIdentityRuntime } from "@/core/identity";
import type { AccountProfile } from "@/core/identity";
import { TestProviders } from "../support/TestProviders";
import { authenticationResponse, deferred, jsonResponse, problemResponse } from "../identity/support";

const initial: AccountProfile = { userId: "00000000-0000-0000-0000-000000000001", email: "alice@example.com", displayName: null };
const active: Array<ReturnType<typeof createIdentityRuntime>> = [];
function renderAccount(fetchImpl: typeof fetch = vi.fn(async () => jsonResponse(initial)) as typeof fetch, signedIn = true) {
  const runtime = createIdentityRuntime({ fetchImpl });
  active.push(runtime);
  if (signedIn) runtime.session.accept(authenticationResponse("first-bearer"));
  const result = render(
    <TestProviders><IdentityProvider runtime={runtime}><AccountPage /></IdentityProvider></TestProviders>,
  );
  return { runtime, fetchImpl, ...result };
}
afterEach(() => { for (const runtime of active.splice(0)) runtime.dispose(); });

async function loadedForm() {
  return screen.findByRole("form", { name: "Account profile" });
}

function nameInput() {
  return screen.getByRole("textbox", { name: /^Display name/i });
}

describe("Account profile page", () => {
  it("requires authentication before issuing any request, and explains expired/rejected sessions", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(initial));
    const { runtime } = renderAccount(fetchImpl as typeof fetch, false);
    expect(screen.getByText(/You're not signed in/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/auth/login");
    expect(fetchImpl).not.toHaveBeenCalled();
    await act(async () => runtime.session.accept(authenticationResponse()));
    await loadedForm();
    await act(async () => runtime.session.invalidate("expired"));
    expect(screen.getByText(/Your session has expired/)).toBeInTheDocument();
    expect(screen.queryByDisplayValue(initial.email)).not.toBeInTheDocument();
    await act(async () => runtime.session.invalidate("unauthorized"));
    expect(screen.getByText(/Your session was rejected/)).toBeInTheDocument();
  });

  it("loads an unset profile, keeps email read-only, and saves Unicode without trimming or HTML interpretation", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      expect(String(path)).toBe("/api/iam/v1/account");
      return jsonResponse(init?.method === "PATCH" ? { ...initial, displayName: JSON.parse(String(init.body)).displayName } : initial);
    });
    renderAccount(fetchImpl as typeof fetch);
    expect(screen.getByRole("status", { name: "Loading account" })).toBeInTheDocument();
    const form = await loadedForm();
    expect(within(form).getByRole("textbox", { name: "Email address" })).toHaveAttribute("readonly");
    expect(within(form).getByDisplayValue(initial.email)).toBeInTheDocument();
    expect(nameInput()).toHaveValue("");
    expect(screen.getByRole("button", { name: "Clear display name" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save display name" })).toBeDisabled();

    const user = userEvent.setup();
    const text = "  <img src=x onerror=alert(1)> 🦊  ";
    await user.type(nameInput(), text);
    await user.click(screen.getByRole("button", { name: "Save display name" }));
    await screen.findByText("Display name saved.");
    expect(nameInput()).toHaveValue(text);
    expect(document.querySelector("img[src='x']")).toBeNull();
    const patch = fetchImpl.mock.calls.find(([, options]) => options?.method === "PATCH");
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ displayName: text, clear: false });
    expect(screen.getByRole("button", { name: "Clear display name" })).toBeEnabled();
  });

  it("clears an existing display name explicitly and uses the returned null projection", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      expect(String(path)).toBe("/api/iam/v1/account");
      return jsonResponse(init?.method === "PATCH" ? initial : { ...initial, displayName: "Alice" });
    });
    renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Clear display name" }));
    await screen.findByText("Display name cleared.");
    expect(nameInput()).toHaveValue("");
    expect(screen.getByRole("button", { name: "Clear display name" })).toBeDisabled();
    const patch = fetchImpl.mock.calls.find(([, options]) => options?.method === "PATCH");
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ displayName: null, clear: true });
  });

  it.each([" ", "\n", "A\u0001B", "\u2028", "\ud800", "a".repeat(81)])(
    "rejects invalid display name %j before sending a mutation", async (invalid) => {
      const fetchImpl = vi.fn(async () => jsonResponse({ ...initial, displayName: "Alice" }));
      renderAccount(fetchImpl as typeof fetch);
      await screen.findByDisplayValue("Alice");
      fireEvent.change(nameInput(), { target: { value: invalid } });
      fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
      expect(await screen.findByText(/Use 1–80 Unicode characters/)).toBeInTheDocument();
      expect(nameInput()).toHaveFocus();
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    },
  );

  it("surfaces backend field errors by code and keeps local text after a rejected update", async () => {
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) => init?.method === "PATCH"
      ? problemResponse(400, "validation_failed", [{ field: "displayName", code: "invalid", message: "Not an allowed name." }])
      : jsonResponse({ ...initial, displayName: "Alice" }));
    renderAccount(fetchImpl as typeof fetch);
    await screen.findByDisplayValue("Alice");
    const user = userEvent.setup();
    await user.clear(nameInput());
    await user.type(nameInput(), "Bob");
    await user.click(screen.getByRole("button", { name: "Save display name" }));
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
    expect(screen.getByRole("button", { name: "Save display name" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Clear display name" })).toBeDisabled();
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    await act(async () => pending.resolve(jsonResponse({ ...initial, displayName: "Bob" })));
    await screen.findByText("Display name saved.");
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
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    expect(await screen.findByText(/could not confirm the update/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save display name" })).toBeDisabled();
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
    await act(async () => runtime.session.accept(authenticationResponse("other-bearer")));
    await loadedForm();
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("shows account_unavailable as a noneditable 403 state", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(403, "account_unavailable"));
    renderAccount(fetchImpl as typeof fetch);
    expect(await screen.findByText(/This account is unavailable/)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Account profile" })).not.toBeInTheDocument();
  });

  it("never displays a previous account while reloading for a new authentication context", async () => {
    const first = deferred<Response>();
    const second = deferred<Response>();
    const fetchImpl = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) =>
      (init?.headers as Headers).get("Authorization") === "Bearer first-bearer" ? first.promise : second.promise);
    const { runtime } = renderAccount(fetchImpl as typeof fetch);
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    await act(async () => runtime.session.accept(authenticationResponse("second-bearer")));
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
    fireEvent.click(screen.getByRole("button", { name: "Save display name" }));
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    await act(async () => runtime.session.accept(authenticationResponse("second-bearer")));
    await screen.findByDisplayValue("bob@example.com");
    await act(async () => pending.resolve(jsonResponse({ ...initial, displayName: "X" })));
    expect(nameInput()).toHaveValue("Bob");
    expect(screen.getByDisplayValue("bob@example.com")).toBeInTheDocument();
    expect(screen.queryByText("Display name saved.")).not.toBeInTheDocument();
  });

  it("has no detectable structural accessibility violations in the loaded profile view", async () => {
    const { container } = renderAccount();
    await loadedForm();
    const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
    expect(results.violations).toEqual([]);
  });
});

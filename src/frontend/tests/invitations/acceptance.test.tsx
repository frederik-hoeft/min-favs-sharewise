import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AuthLayout from "@/app/(auth)/layout";
import { InvitationAcceptance } from "@/app/(auth)/invite/InvitationAcceptance";
import { AuthenticationForm } from "@/app/(auth)/_components/AuthenticationForm";
import { IdentityProvider } from "@/app/identity-provider";
import { InvitationContinuationProvider, useInvitationContinuation } from "@/app/invitation-continuation";
import { createIdentityRuntime, type IdentityRuntime } from "@/core/identity";
import { TestProviders } from "../support/TestProviders";
import { authenticationResponse, ControlledLocks, SESSION_TWO } from "../identity/support";
import { deferred, GROUP_ID, json, problem, scriptedClient } from "../groups/support";
import type { ReactNode } from "react";

const TOKEN = `${GROUP_ID}.${"A".repeat(43)}`;
const PREVIEW = "/api/groups/v1/invitations/preview";
const REDEEM = "/api/groups/v1/invitations/redeem";
const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => window.location.pathname,
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

let fake = scriptedClient();
let runtime: IdentityRuntime;
beforeEach(() => {
  fake = scriptedClient();
  runtime = createIdentityRuntime({ fetchImpl: fake.fetchImpl, locks: () => new ControlledLocks().manager });
  window.history.replaceState({}, "", `/invite#token=${TOKEN}`);
  replace.mockReset();
});
afterEach(() => { runtime.dispose(); window.history.replaceState({}, "", "/"); });

function Root({ children }: { children: ReactNode }) {
  return <TestProviders><IdentityProvider runtime={runtime}><InvitationContinuationProvider>{children}</InvitationContinuationProvider></IdentityProvider></TestProviders>;
}
function renderInvite(signedIn = true, preview = json(200, { groupName: "Mallorca 2027" })) {
  if (signedIn) fake.respond("POST", PREVIEW, preview);
  if (signedIn) runtime.session.accept(authenticationResponse());
  else runtime.session.invalidate("expired");
  return render(<Root><InvitationAcceptance /></Root>);
}

describe("GI-FT17 invitation continuation", () => {
  it("removes the fragment and explicitly redeems with the current session", async () => {
    fake.respond("POST", REDEEM, json(200, { groupId: GROUP_ID, outcome: "joined" }));
    renderInvite();
    const button = await screen.findByRole("button", { name: "Join group" });
    expect(window.location.hash).toBe("");
    expect(JSON.stringify(window.history.state)).not.toContain(TOKEN);
    expect(screen.getByText("Mallorca 2027")).toBeVisible();
    expect(fake.requests).toEqual([{ method: "POST", path: PREVIEW, body: { token: TOKEN } }]);
    await userEvent.setup().click(button);
    await waitFor(() => expect(replace).toHaveBeenCalledWith(`/app/groups/view?id=${GROUP_ID}`));
    expect(fake.requests).toEqual([{ method: "POST", path: PREVIEW, body: { token: TOKEN } }, { method: "POST", path: REDEEM, body: { token: TOKEN } }]);
    expect(screen.queryByRole("button", { name: "Join group" })).not.toBeInTheDocument();
  });

  it("preserves the preview and explicit join when using the actual layout skip link", async () => {
    runtime.session.accept(authenticationResponse());
    fake.respond("POST", PREVIEW, json(200, { groupName: "Mallorca 2027" }));
    fake.respond("POST", REDEEM, json(200, { groupId: GROUP_ID, outcome: "joined" }));
    render(<Root><AuthLayout><InvitationAcceptance /></AuthLayout></Root>);
    await screen.findByRole("button", { name: "Join group" });
    const user = userEvent.setup();
    await user.tab();
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveFocus();
    const hashChanged = new Promise<void>(resolve => {
      window.addEventListener("hashchange", () => resolve(), { once: true });
    });
    await user.keyboard("{Enter}");
    await act(async () => { await hashChanged; });
    expect(window.location.hash).toBe("#main");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main");
    expect(screen.getByText("Mallorca 2027")).toBeVisible();
    expect(fake.requests).toEqual([{ method: "POST", path: PREVIEW, body: { token: TOKEN } }]);
    await user.click(screen.getByRole("button", { name: "Join group" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith(`/app/groups/view?id=${GROUP_ID}`));
    expect(fake.requests.at(-1)).toEqual({ method: "POST", path: REDEEM, body: { token: TOKEN } });
  });

  it.each(["#token=", "#token=bad-token"])("clears a pending invitation on malformed replacement %s", async hash => {
    renderInvite();
    await screen.findByRole("button", { name: "Join group" });
    window.history.replaceState({}, "", `/invite${hash}`);
    act(() => window.dispatchEvent(new HashChangeEvent("hashchange")));
    expect(await screen.findByText(/invitation is unavailable/i)).toBeVisible();
    expect(window.location.hash).toBe("");
    expect(screen.queryByRole("button", { name: "Join group" })).not.toBeInTheDocument();
    expect(fake.requests).toHaveLength(1);
  });

  it("leaves unrelated fragments untouched without inventing a continuation", async () => {
    window.history.replaceState({}, "", "/invite#main");
    renderInvite(false);
    expect(await screen.findByText(/invitation is unavailable/i)).toBeVisible();
    expect(window.location.hash).toBe("#main");
    expect(fake.requests).toHaveLength(0);
  });

  it("discards a capability when the session changes while redemption is pending", async () => {
    const response = deferred<Response>();
    fake.respond("POST", REDEEM, response.promise);
    renderInvite();
    await userEvent.setup().click(await screen.findByRole("button", { name: "Join group" }));
    await waitFor(() => expect(fake.requests.filter(request => request.path === REDEEM)).toHaveLength(1));
    act(() => runtime.session.accept(authenticationResponse("new-token", undefined, SESSION_TWO)));
    await act(async () => { response.resolve(json(200, { groupId: GROUP_ID, outcome: "joined" })); });
    expect(replace).not.toHaveBeenCalled();
    expect(screen.getByText(/invitation is unavailable/i)).toBeVisible();
    expect(screen.queryByRole("button", { name: "Join group" })).not.toBeInTheDocument();
  });

  it("clears uniformly rejected capabilities and allows explicit recovery after uncertain writes", async () => {
    fake.respond("POST", REDEEM, new Error("connection lost"), problem(404, "group_invitation_unavailable"));
    renderInvite();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Join group" }));
    expect(await screen.findByText(/join could not be confirmed/i)).toBeVisible();
    expect(fake.requests.filter(request => request.path === REDEEM)).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Join group" }));
    expect(await screen.findByText(/invitation is unavailable/i)).toBeVisible();
    expect(fake.requests.filter(request => request.path === REDEEM)).toHaveLength(2);
    expect(replace).not.toHaveBeenCalled();
  });

  it("does not offer joining until a valid preview is available, and clears an unavailable link", async () => {
    renderInvite(true, problem(404, "group_invitation_unavailable"));
    await screen.findByText(/invitation is unavailable/i);
    expect(screen.queryByRole("button", { name: "Join group" })).not.toBeInTheDocument();
    expect(fake.requests.filter(request => request.path === REDEEM)).toHaveLength(0);
  });

  it("ignores a rejected preview of an invitation that was replaced in the same session", async () => {
    const old = deferred<Response>();
    runtime.session.accept(authenticationResponse());
    fake.respond("POST", PREVIEW, old.promise, json(200, { groupName: "Replacement group" }));
    render(<Root><InvitationAcceptance /></Root>);
    await waitFor(() => expect(fake.requests).toHaveLength(1));
    const replacementToken = `${GROUP_ID}.${"B".repeat(42)}A`;
    window.history.replaceState({}, "", `/invite#token=${replacementToken}`);
    act(() => window.dispatchEvent(new HashChangeEvent("hashchange")));
    expect(await screen.findByText("Replacement group")).toBeVisible();
    await act(async () => { old.resolve(problem(404, "group_invitation_unavailable")); });
    expect(screen.getByText("Replacement group")).toBeVisible();
    expect(screen.getByRole("button", { name: "Join group" })).toBeEnabled();
    expect(fake.requests).toEqual([
      { method: "POST", path: PREVIEW, body: { token: TOKEN } },
      { method: "POST", path: PREVIEW, body: { token: replacementToken } },
    ]);
  });

  it("does not disclose a delayed preview to a replacement session", async () => {
    const old = deferred<Response>();
    runtime.session.accept(authenticationResponse());
    fake.respond("POST", PREVIEW, old.promise);
    render(<Root><InvitationAcceptance /></Root>);
    await waitFor(() => expect(fake.requests).toHaveLength(1));
    act(() => runtime.session.accept(authenticationResponse("new-token", undefined, SESSION_TWO)));
    await act(async () => { old.resolve(json(200, { groupName: "Private group" })); });
    expect(screen.queryByText("Private group")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Join group" })).not.toBeInTheDocument();
    expect(screen.getByText(/invitation is unavailable/i)).toBeVisible();
  });

  it("allows a preview retry without redeeming", async () => {
    renderInvite(true, problem(500, "internal_error"));
    const retry = await screen.findByRole("button", { name: "Retry invitation check" });
    expect(screen.queryByRole("button", { name: "Join group" })).not.toBeInTheDocument();
    fake.respond("POST", PREVIEW, json(200, { groupName: "Trip" }));
    await userEvent.setup().click(retry);
    expect(await screen.findByText("Trip")).toBeVisible();
    expect(fake.requests.filter(request => request.path === REDEEM)).toHaveLength(0);
  });

  it("preserves the exact nonsecret continuation when switching login and registration", async () => {
    const view = renderInvite(false);
    const login = await screen.findByRole("link", { name: "Sign in" });
    expect(login).toHaveAttribute("href", "/auth/login?next=%2Finvite");
    expect(screen.getByRole("link", { name: "Create account" })).toHaveAttribute("href", "/auth/register?next=%2Finvite");
    expect(fake.requests).toHaveLength(0);
    window.history.replaceState({}, "", "/auth/login?next=%2Finvite");
    view.rerender(<Root><AuthenticationForm mode="login" /></Root>);
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/auth/register?next=%2Finvite");
    window.history.replaceState({}, "", "/auth/register?next=%2Finvite");
    view.rerender(<Root><AuthenticationForm mode="register" /></Root>);
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/auth/login?next=%2Finvite");
    expect(window.location.href).not.toContain(TOKEN);
    expect(fake.requests).toHaveLength(0);
  });

  it.each(["login", "register"] as const)("returns from successful %s without automatically redeeming", async mode => {
    const view = renderInvite(false);
    await screen.findByRole("link", { name: "Sign in" });
    window.history.replaceState({}, "", `/auth/${mode}?next=%2Finvite`);
    view.rerender(<Root><AuthenticationForm mode={mode} /></Root>);
    fake.respond("GET", "/api/iam/v1/auth/csrf", json(200, { token: "csrf" }));
    fake.respond("POST", `/api/iam/v1/auth/${mode}`, json(mode === "register" ? 201 : 200, authenticationResponse()));
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "Email address" }), "person@example.com");
    await user.type(screen.getByLabelText(/^Password/), "correct horse battery staple");
    await user.click(screen.getByRole("button", { name: mode === "register" ? "Create account" : "Sign in" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/invite"));
    fake.respond("POST", PREVIEW, json(200, { groupName: "Mallorca 2027" }));
    window.history.replaceState({}, "", "/invite");
    view.rerender(<Root><InvitationAcceptance /></Root>);
    await screen.findByRole("button", { name: "Join group" });
    expect(fake.requests.filter(request => request.path === REDEEM)).toHaveLength(0);
    expect(window.location.href).not.toContain(TOKEN);
  });

  it("does not accept malformed fragments or a fabricated continuation return path", async () => {
    window.history.replaceState({}, "", "/invite#token=bad-token");
    const view = renderInvite(false);
    expect(await screen.findByText(/invitation is unavailable/i)).toBeVisible();
    expect(window.location.hash).toBe("");
    window.history.replaceState({}, "", "/auth/login?next=%2Finvite");
    view.rerender(<Root><AuthenticationForm mode="login" /></Root>);
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/auth/register");
    fake.respond("GET", "/api/iam/v1/auth/csrf", json(200, { token: "csrf" }));
    fake.respond("POST", "/api/iam/v1/auth/login", json(200, authenticationResponse()));
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "Email address" }), "person@example.com");
    await user.type(screen.getByLabelText(/^Password/), "correct horse battery staple");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/app"));
  });

  it("discards the continuation on cancellation and on unrelated navigation", async () => {
    const view = renderInvite(false);
    const cancel = await screen.findByRole("link", { name: "Cancel" });
    // jsdom cannot navigate documents; observe the explicit cancellation without leaving the test document.
    cancel.addEventListener("click", event => event.preventDefault());
    fireEvent.click(cancel);
    expect(screen.getByText(/invitation is unavailable/i)).toBeVisible();
    expect(fake.requests).toHaveLength(0);
    // A new link can be opened, but not retained through unrelated navigation.
    window.history.replaceState({}, "", `/invite#token=${TOKEN}`);
    act(() => window.dispatchEvent(new HashChangeEvent("hashchange")));
    await screen.findByRole("link", { name: "Sign in" });
    function PendingIndicator() { return <span>{useInvitationContinuation().pending ? "pending" : "empty"}</span>; }
    window.history.replaceState({}, "", "/auth/login?next=/app");
    view.rerender(<Root><PendingIndicator /></Root>);
    expect(await screen.findByText("empty")).toBeVisible();
  });
});

describe("Invitation continuation secret hygiene", () => {
  it("does not serialize a captured secret, even accidentally", async () => {
    const { InvitationContinuation } = await import("@/app/invitation-continuation");
    const continuation = new InvitationContinuation();
    continuation.capture(TOKEN);
    continuation.bind(1);
    expect(continuation.forSession(1)).toBe(TOKEN);
    expect(JSON.stringify(continuation)).not.toContain(TOKEN);
    continuation.invalidateIfChanged(2);
    expect(continuation.pending).toBe(false);
    expect(continuation.forSession(1)).toBeNull();
  });

  it("does not navigate when an abandoned authentication form completes", async () => {
    const view = renderInvite(false);
    await screen.findByRole("link", { name: "Sign in" });
    window.history.replaceState({}, "", "/auth/login?next=%2Finvite");
    view.rerender(<Root><AuthenticationForm mode="login" /></Root>);
    const pending = deferred<Response>();
    fake.respond("GET", "/api/iam/v1/auth/csrf", json(200, { token: "csrf" }));
    fake.respond("POST", "/api/iam/v1/auth/login", pending.promise);
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "Email address" }), "person@example.com");
    await user.type(screen.getByLabelText(/^Password/), "correct horse battery staple");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(fake.requests.some(request => request.method === "POST")).toBe(true));
    window.history.replaceState({}, "", "/");
    view.rerender(<Root><span>Home</span></Root>);
    await act(async () => { pending.resolve(json(200, authenticationResponse())); });
    expect(replace).not.toHaveBeenCalled();
    expect(screen.getByText("Home")).toBeVisible();
  });
});

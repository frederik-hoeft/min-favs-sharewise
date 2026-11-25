import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import AccountPage from "@/app/(app)/app/(standard)/account/page";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";
import { createIdentityRuntime } from "@/core/identity";
import { TestProviders } from "../support/TestProviders";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/app/account",
}));
import { SESSION_TWO, authenticationResponse, deferred, jsonResponse, problemResponse } from "../identity/support";

const profile = { userId: "00000000-0000-0000-0000-000000000001", email: "alice@example.com", displayName: "Alice" };
const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function mount(passwordResponse: () => Promise<Response> = async () => new Response(null, { status: 204 }), signedIn = true) {
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ path: String(path), init });
    return String(path).endsWith("/auth/password") ? passwordResponse() : jsonResponse(profile);
  });
  const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch });
  runtimes.push(runtime);
  if (signedIn) runtime.session.accept(authenticationResponse("alice-bearer"));
  const result = render(
    <TestProviders><IdentityProvider runtime={runtime}><RequireAuthentication><AccountPage /></RequireAuthentication></IdentityProvider></TestProviders>,
  );
  return { runtime, requests, fetchImpl, ...result };
}
afterEach(() => { replace.mockReset(); for (const runtime of runtimes.splice(0)) runtime.dispose(); });

function passwordForm() { return screen.getByRole("form", { name: "Change password" }); }
function currentInput() { return within(passwordForm()).getByLabelText("Current password") as HTMLInputElement; }
function newInput() { return within(passwordForm()).getByLabelText(/New password/i) as HTMLInputElement; }
function changeButton() { return within(passwordForm()).getByRole("button", { name: "Change password" }); }
async function enterPasswords(current = "old-password", next = "new-password-123") {
  const user = userEvent.setup();
  await user.type(currentInput(), current);
  await user.type(newInput(), next);
  return user;
}
function passwordPosts(requests: Array<{ path: string; init?: RequestInit }>) {
  return requests.filter(({ path }) => path.endsWith("/auth/password"));
}

describe("Account password-change page", () => {
  it("uses full-width sections, responsive two-column field grids and right-aligned actions", async () => {
    const { container } = mount();
    await screen.findByRole("form", { name: "Account profile" });
    expect(screen.getByText("Your account")).toBeInTheDocument();
    const profileForm = screen.getByRole("form", { name: "Account profile" });
    const profileGrid = profileForm.querySelector(".mantine-SimpleGrid-root");
    const passwordGrid = passwordForm().querySelector(".mantine-SimpleGrid-root");
    expect(profileGrid).toBeTruthy();
    expect(passwordGrid).toBeTruthy();
    expect(profileGrid!.querySelectorAll("input")).toHaveLength(2);
    expect(passwordGrid!.querySelectorAll("input")).toHaveLength(2);
    expect(profileForm.querySelector(".mantine-Group-root")).toHaveStyle("--group-justify: flex-end");
    expect(passwordForm().querySelector(".mantine-Group-root")).toHaveStyle("--group-justify: flex-end");
    expect(container.querySelectorAll(".mantine-Card-root")).toHaveLength(2);
    expect(currentInput()).toHaveAttribute("autocomplete", "current-password");
    expect(newInput()).toHaveAttribute("autocomplete", "new-password");
    expect(newInput()).toHaveAttribute("maxlength", "256");
    expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
  });

  it("uses the authenticated identity API and clears both sensitive inputs on confirmed 204", async () => {
    const { requests, runtime } = mount();
    const user = await enterPasswords();
    await user.click(changeButton());
    expect(await screen.findByText(/This session stays signed in. Other sessions may remain active until their current access tokens expire/)).toBeInTheDocument();
    expect(currentInput()).toHaveValue("");
    expect(newInput()).toHaveValue("");
    expect(runtime.session.getAccessToken()).toBe("alice-bearer");
    expect(passwordPosts(requests)).toHaveLength(1);
    const submission = passwordPosts(requests)[0];
    expect(JSON.parse(String(submission.init?.body))).toEqual({ currentPassword: "old-password", newPassword: "new-password-123" });
    expect((submission.init?.headers as Headers).get("Authorization")).toBe("Bearer alice-bearer");
    expect((submission.init?.headers as Headers).has("X-XSRF-TOKEN")).toBe(false);
    expect(screen.getByRole("status", { name: "Password updated" })).toBeInTheDocument();
  });

  it("submits with the keyboard and announces the result without requiring a pointer", async () => {
    const { requests } = mount();
    const user = userEvent.setup();
    await user.type(currentInput(), "old-password");
    await user.type(newInput(), "replacement-password{Enter}");
    expect(await screen.findByRole("status", { name: "Password updated" })).toHaveFocus();
    expect(passwordPosts(requests)).toHaveLength(1);
  });

  it("validates short, equal, malformed and oversized passwords without submitting", async () => {
    const { requests } = mount();
    fireEvent.submit(passwordForm());
    expect(await screen.findByText("Enter your current password.")).toBeInTheDocument();
    expect(screen.getByText("Enter a new password.")).toBeInTheDocument();
    fireEvent.change(currentInput(), { target: { value: "same-password" } });
    fireEvent.change(newInput(), { target: { value: "same-password" } });
    fireEvent.submit(passwordForm());
    expect(screen.getByText(/different from your current password/)).toBeInTheDocument();
    fireEvent.change(newInput(), { target: { value: "short" } });
    fireEvent.submit(passwordForm());
    expect(screen.getByText("Use at least 8 characters.")).toBeInTheDocument();
    fireEvent.change(newInput(), { target: { value: "abc\ud800defghi" } });
    fireEvent.submit(passwordForm());
    expect(screen.getByText("New password contains invalid characters.")).toBeInTheDocument();
    fireEvent.change(newInput(), { target: { value: "a".repeat(257) } });
    fireEvent.submit(passwordForm());
    expect(screen.getByText("New password must not exceed 256 characters.")).toBeInTheDocument();
    expect(passwordPosts(requests)).toHaveLength(0);
  });

  it("maps structured server validation errors to the relevant input and focuses it", async () => {
    mount(async () => problemResponse(400, "validation_failed", [
      { field: "newPassword", code: "invalid", message: "Server rejected the replacement." },
    ]));
    const user = await enterPasswords();
    await user.click(changeButton());
    expect(await screen.findByText("Server rejected the replacement.")).toBeInTheDocument();
    await waitFor(() => expect(newInput()).toHaveFocus());
    expect(screen.getByRole("alert", { name: "Password change unsuccessful" })).toBeInTheDocument();
    expect(currentInput()).toHaveValue("old-password");
  });

  it("reports incorrect current password or lockout without changing credentials", async () => {
    const { requests } = mount(async () => problemResponse(400, "current_password_invalid"));
    const user = await enterPasswords();
    await user.click(changeButton());
    expect(await screen.findByText(/current password is incorrect, or your account is temporarily locked/)).toBeInTheDocument();
    await waitFor(() => expect(currentInput()).toHaveFocus());
    expect(passwordPosts(requests)).toHaveLength(1);
    expect(changeButton()).toBeEnabled();
  });

  it.each([
    [409, "credential_changed", /credentials changed during this request/, true],
    [403, "account_unavailable", /This account is unavailable/, false],
  ] as const)("handles a confirmed %i %s denial without replaying", async (status, code, message, signIn) => {
    const { requests } = mount(async () => problemResponse(status, code));
    const user = await enterPasswords();
    await user.click(changeButton());
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(changeButton()).toBeDisabled();
    expect(currentInput()).toHaveValue("");
    expect(newInput()).toHaveValue("");
    expect(screen.queryByRole("link", { name: "Sign in" }) !== null).toBe(signIn);
    fireEvent.submit(passwordForm());
    expect(passwordPosts(requests)).toHaveLength(1);
  });

  it.each(["network", "gateway", "unexpected"] as const)("treats %s outcomes as uncertain and blocks blind retry", async (kind) => {
    const response = kind === "network" ? async (): Promise<Response> => { throw new TypeError("sensitive network detail"); }
      : kind === "gateway" ? async () => problemResponse(502, "gateway_failure")
        : async () => jsonResponse({ unexpected: true });
    const { requests } = mount(response);
    const user = await enterPasswords();
    await user.click(changeButton());
    expect(await screen.findByText(/couldn't confirm whether your password changed/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Reload account" })).toHaveAttribute("href", "/app/account");
    expect(screen.getByRole("link", { name: "Sign in instead" })).toHaveAttribute("href", "/auth/login");
    expect(currentInput()).toHaveValue("");
    expect(newInput()).toHaveValue("");
    expect(changeButton()).toBeDisabled();
    fireEvent.submit(passwordForm());
    expect(passwordPosts(requests)).toHaveLength(1);
  });

  it("preserves password drafts on same-session bearer rotation", async () => {
    const { runtime, requests } = mount();
    await enterPasswords();
    await act(async () => runtime.session.accept(authenticationResponse("renewed")));
    expect(currentInput()).toHaveValue("old-password");
    expect(newInput()).toHaveValue("new-password-123");
    fireEvent.submit(passwordForm());
    await screen.findByRole("status", { name: "Password updated" });
    expect(passwordPosts(requests)).toHaveLength(1);
    expect((passwordPosts(requests)[0].init?.headers as Headers).get("Authorization")).toBe("Bearer renewed");
  });

  it("disables inputs and ignores repeated submissions while a password mutation is pending", async () => {
    const reply = deferred<Response>();
    const { requests, runtime } = mount(() => reply.promise);
    const user = await enterPasswords();
    await user.click(changeButton());
    await waitFor(() => expect(passwordPosts(requests)).toHaveLength(1));
    expect(currentInput()).toBeDisabled();
    expect(newInput()).toBeDisabled();
    expect(changeButton()).toBeDisabled();
    await act(async () => runtime.session.accept(authenticationResponse("renewed")));
    expect(currentInput()).toBeDisabled();
    expect(newInput()).toBeDisabled();
    expect(changeButton()).toBeDisabled();
    fireEvent.submit(passwordForm());
    expect(passwordPosts(requests)).toHaveLength(1);
    await act(async () => reply.resolve(new Response(null, { status: 204 })));
    expect(await screen.findByText(/they cannot refresh and must then sign in again/)).toBeInTheDocument();
  });

  it("unmounts password fields on logout/context change and never applies a late old-session result", async () => {
    const reply = deferred<Response>();
    const { runtime, requests } = mount(() => reply.promise);
    const user = await enterPasswords();
    await user.click(changeButton());
    await waitFor(() => expect(passwordPosts(requests)).toHaveLength(1));
    await act(async () => runtime.session.markContextChanged());
    expect(screen.queryByRole("form", { name: "Change password" })).not.toBeInTheDocument();
    expect(screen.getByText(/Another sign-in changed the browser session/)).toBeInTheDocument();
    await act(async () => runtime.session.accept(authenticationResponse("bob-bearer", undefined, SESSION_TWO)));
    expect(currentInput()).toHaveValue("");
    expect(newInput()).toHaveValue("");
    await act(async () => reply.resolve(new Response(null, { status: 204 })));
    expect(screen.queryByText(/Password changed/)).not.toBeInTheDocument();
    expect(currentInput()).toHaveValue("");
    await act(async () => runtime.session.invalidate("expired"));
    expect(screen.queryByRole("form", { name: "Change password" })).not.toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Faccount"));
  });

  it("does not render credentials controls during initial restoration", async () => {
    const { runtime } = mount(undefined, false);
    expect(screen.queryByRole("form", { name: "Change password" })).not.toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
    await act(async () => runtime.session.markRestoring());
    expect(screen.queryByRole("form", { name: "Change password" })).not.toBeInTheDocument();
  });

  it("redirects when password change confirms the session was revoked, without replay", async () => {
    const { requests, runtime } = mount(async () => problemResponse(401, "session_unavailable"));
    const user = await enterPasswords();
    await user.click(changeButton());
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Faccount"));
    expect(runtime.session.getAccessToken()).toBeUndefined();
    expect(screen.queryByRole("form", { name: "Change password" })).not.toBeInTheDocument();
    expect(passwordPosts(requests)).toHaveLength(1);
  });

});

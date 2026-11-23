import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";
import { createIdentityRuntime } from "@/core/identity";
import { TestProviders } from "../support/TestProviders";
import { ControlledLocks, authenticationResponse, jsonResponse, problemResponse } from "../identity/support";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/app/account",
}));

const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function mount(runtime: ReturnType<typeof createIdentityRuntime>) {
  runtimes.push(runtime);
  return render(
    <TestProviders><IdentityProvider runtime={runtime}>
      <RequireAuthentication><div>Protected account content</div></RequireAuthentication>
    </IdentityProvider></TestProviders>,
  );
}

afterEach(() => {
  for (const runtime of runtimes.splice(0)) runtime.dispose();
  replace.mockReset();
  window.history.replaceState({}, "", "/");
});

describe("authenticated route guard", () => {
  it("renders private content only for an authenticated session", async () => {
    const runtime = createIdentityRuntime({ fetchImpl: vi.fn() });
    runtime.session.accept(authenticationResponse("current"));
    mount(runtime);
    expect(screen.getByText("Protected account content")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
    await act(async () => runtime.session.invalidate("unauthorized"));
    expect(screen.queryByText("Protected account content")).not.toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Faccount"));
  });

  it("preserves an app-local return path, including query, for terminal reauthentication", async () => {
    window.history.replaceState({}, "", "/app/account?section=security");
    const runtime = createIdentityRuntime({ fetchImpl: vi.fn() });
    runtime.session.invalidate("expired");
    mount(runtime);
    await waitFor(() => expect(replace).toHaveBeenCalledWith(
      "/auth/login?next=%2Fapp%2Faccount%3Fsection%3Dsecurity",
    ));
  });

  it("does not redirect when restoration fails before refresh submission; retry stays available", async () => {
    const fetchImpl = vi.fn();
    const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch, locks: () => undefined });
    mount(runtime);
    expect(screen.queryByText("Protected account content")).not.toBeInTheDocument();
    await screen.findByRole("button", { name: "Retry session check" });
    expect(replace).not.toHaveBeenCalled();
    expect(fetchImpl).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Retry session check" }));
    expect(replace).not.toHaveBeenCalled();
  });

  it("suspends the old account on a cross-tab context switch, without navigating under another identity", async () => {
    const runtime = createIdentityRuntime({ fetchImpl: vi.fn() });
    runtime.session.accept(authenticationResponse("current"));
    mount(runtime);
    await act(async () => runtime.session.markContextChanged());
    expect(screen.getByText(/Another sign-in changed the browser session/)).toBeInTheDocument();
    expect(screen.queryByText("Protected account content")).not.toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("redirects after shared HTTP recovery confirms an expired bearer cannot refresh", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL) => {
      if (String(path).endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      return problemResponse(401, String(path).endsWith("/refresh") ? "refresh_token_invalid" : "invalid_credential");
    });
    const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch,
      locks: () => new ControlledLocks().manager });
    runtime.session.accept(authenticationResponse("expired"));
    mount(runtime);
    // The client performs bounded recovery; the guard observes its terminal state.
    await expect(runtime.api.get("/iam/v1/account")).rejects.toMatchObject({ status: 401 });
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Faccount"));
    expect(runtime.session.getAccessToken()).toBeUndefined();
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });
  it("does not redirect after an application-level 401 that does not invalidate the session", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(401, "unauthenticated"));
    const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch });
    runtime.session.accept(authenticationResponse("current"));
    mount(runtime);
    await expect(runtime.api.get("/iam/v1/account")).rejects.toMatchObject({ status: 401, code: "unauthenticated" });
    expect(runtime.session.getSnapshot().status).toBe("authenticated");
    expect(screen.getByText("Protected account content")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

});

import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { IdentityProvider } from "@/app/identity-provider";
import AppLayout from "@/app/(app)/app/layout";
import { createIdentityRuntime } from "@/core/identity";
import { TestProviders } from "./support/TestProviders";
import { ControlledLocks, authenticationResponse, jsonResponse, problemResponse } from "./identity/support";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
let pathname = "/app/account";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => pathname,
}));

const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function mount(runtime: ReturnType<typeof createIdentityRuntime>) {
  runtimes.push(runtime);
  return render(
    <TestProviders><IdentityProvider runtime={runtime}>
      <AppLayout><div>Protected workspace content</div></AppLayout>
    </IdentityProvider></TestProviders>,
  );
}

afterEach(() => {
  for (const runtime of runtimes.splice(0)) runtime.dispose();
  replace.mockReset();
  pathname = "/app/account";
  window.history.replaceState({}, "", "/");
});

describe("authenticated workspace layout", () => {
  it.each(["/app", "/app/dummy1", "/app/dummy3/international-collaboration-project", "/app/account"])(
    "redirects unauthenticated visitors to sign-in before rendering the %s workspace shell", async (route) => {
      pathname = route;
      window.history.replaceState({}, "", route);
      const runtime = createIdentityRuntime({ fetchImpl: vi.fn(), locks: () => undefined });
      runtime.session.invalidate("unauthorized");
      mount(runtime);
      expect(screen.queryByText("Protected workspace content")).not.toBeInTheDocument();
      expect(screen.queryByRole("navigation", { name: "Workspace navigation" })).not.toBeInTheDocument();
      await waitFor(() => expect(replace).toHaveBeenCalledWith(
        `/auth/login?next=${encodeURIComponent(route)}`,
      ));
    },
  );

  it("restores an existing cookie session before showing a nested workspace route", async () => {
    pathname = "/app/dummy2";
    const fetchImpl = vi.fn(async (path: RequestInfo | URL) =>
      String(path).endsWith("/csrf") ? jsonResponse({ token: "csrf" }) : jsonResponse(authenticationResponse("restored")),
    );
    const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch,
      locks: () => new ControlledLocks().manager });
    mount(runtime);
    expect(screen.queryByText("Protected workspace content")).not.toBeInTheDocument();
    await screen.findByText("Protected workspace content");
    expect(runtime.session.getAccessToken()).toBe("restored");
    expect(replace).not.toHaveBeenCalled();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("renders private content only for an authenticated session", async () => {
    const runtime = createIdentityRuntime({ fetchImpl: vi.fn() });
    runtime.session.accept(authenticationResponse("current"));
    mount(runtime);
    expect(screen.getByText("Protected workspace content")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Workspace navigation" })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
    await act(async () => runtime.session.invalidate("unauthorized"));
    expect(screen.queryByText("Protected workspace content")).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Workspace navigation" })).not.toBeInTheDocument();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/auth/login?next=%2Fapp%2Faccount"));
  });

  it("preserves an app-local return path, including query, for terminal reauthentication", async () => {
    pathname = "/app/dummy3/international-collaboration-project";
    window.history.replaceState({}, "", `${pathname}?tab=expenses`);
    const runtime = createIdentityRuntime({ fetchImpl: vi.fn() });
    runtime.session.invalidate("expired");
    mount(runtime);
    await waitFor(() => expect(replace).toHaveBeenCalledWith(
      "/auth/login?next=%2Fapp%2Fdummy3%2Finternational-collaboration-project%3Ftab%3Dexpenses",
    ));
  });

  it("does not redirect when restoration fails before refresh submission; retry stays available", async () => {
    const fetchImpl = vi.fn();
    const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch, locks: () => undefined });
    mount(runtime);
    expect(screen.queryByText("Protected workspace content")).not.toBeInTheDocument();
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
    expect(screen.queryByText("Protected workspace content")).not.toBeInTheDocument();
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
    expect(screen.getByText("Protected workspace content")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});

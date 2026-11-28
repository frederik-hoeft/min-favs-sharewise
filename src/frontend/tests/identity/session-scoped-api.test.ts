import { afterEach, describe, expect, it, vi } from "vitest";
import { createIdentityRuntime } from "@/core/identity";
import { authenticationResponse, jsonResponse, problemResponse, SESSION_ONE, SESSION_TWO } from "./support";

const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function setup() {
  const fetchImpl = vi.fn<typeof fetch>(async () => jsonResponse({ ok: true }));
  const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch });
  runtimes.push(runtime);
  runtime.session.accept(authenticationResponse("first"));
  return { runtime, fetchImpl, apis: runtime.forSession(SESSION_ONE) };
}
afterEach(() => { for (const runtime of runtimes.splice(0)) runtime.dispose(); });

describe("Session-scoped application APIs", () => {
  it.each(["logout", "denial", "context-change", "replacement"] as const)("rejects every retained typed or generic API after %s", async (event) => {
    const { runtime, fetchImpl, apis } = setup();
    switch (event) {
      case "logout": runtime.session.clear(); break;
      case "denial": runtime.session.invalidate("unauthorized"); break;
      case "context-change": runtime.session.markContextChanged(); break;
      case "replacement": runtime.session.accept(authenticationResponse("replacement", undefined, SESSION_TWO)); break;
    }
    for (const operation of [
      () => apis.api.request("/finance/v1/expenses", { method: "POST" }),
      () => apis.api.get("/finance/v1/expenses"),
      () => apis.api.post("/finance/v1/expenses", {}),
      () => apis.api.put("/finance/v1/expenses", {}),
      () => apis.api.patch("/finance/v1/expenses", {}),
      () => apis.api.delete("/finance/v1/expenses"),
      () => apis.account.setDisplayName("old draft"),
      () => apis.security.changePassword({ currentPassword: "old", newPassword: "new password" }),
    ]) {
      await expect(operation()).rejects.toMatchObject({ code: "session_context_changed" });
    }
    expect(fetchImpl).not.toHaveBeenCalled();
    runtime.session.accept(authenticationResponse("new-login", undefined, SESSION_TWO));
    await expect(apis.api.post("/finance/v1/expenses", {})).rejects.toMatchObject({ code: "session_context_changed" });
    await expect(runtime.forSession(SESSION_TWO).api.post("/finance/v1/expenses", {})).resolves.toEqual({ ok: true });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect((fetchImpl.mock.calls[0][1]?.headers as Headers).get("Authorization")).toBe("Bearer new-login");
  });

  it("never reactivates an ended facade even if the same session ID is accepted again", async () => {
    const { runtime, fetchImpl, apis } = setup();
    runtime.session.invalidate("unauthorized");
    runtime.session.accept(authenticationResponse("different-lifetime"));
    await expect(apis.api.get("/groups/v1/groups")).rejects.toMatchObject({ code: "session_context_changed" });
    await expect(runtime.forSession(SESSION_ONE).api.get("/groups/v1/groups")).resolves.toEqual({ ok: true });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it.each(["csrf", "register", "login", "refresh", "logout"])("blocks query-bearing cookie-backed %s from workspace APIs", async (endpoint) => {
    const { runtime, fetchImpl, apis } = setup();
    const path = `/iam/v1/auth/${endpoint}?example=1`;
    await expect(apis.api.post(path, {})).rejects.toMatchObject({ code: "invalid_request_context" });
    expect(fetchImpl).not.toHaveBeenCalled();
    await expect(runtime.api.post(path, {})).resolves.toEqual({ ok: true });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0][0]).toBe(`${location.origin}/api${path}`);
    expect((fetchImpl.mock.calls[0][1]?.headers as Headers).has("Authorization")).toBe(false);
    fetchImpl.mockResolvedValueOnce(problemResponse(401, "invalid_credential"));
    await expect(runtime.api.post(path, {})).rejects.toMatchObject({ status: 401, code: "invalid_credential" });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(runtime.session.getAccessToken()).toBe("first");
  });

  it("does not permit workspace adapters to bypass the session bearer or invoke cookie-backed authentication", async () => {
    const { fetchImpl, apis } = setup();
    await expect(apis.api.get("/groups/v1/groups", { headers: { Authorization: "Bearer other" } })).rejects.toMatchObject({ code: "invalid_request_context" });
    await expect(apis.api.post("/iam/v1/auth/logout", {})).rejects.toMatchObject({ code: "invalid_request_context" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});

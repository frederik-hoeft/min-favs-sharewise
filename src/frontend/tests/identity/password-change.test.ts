import { afterEach, describe, expect, it, vi } from "vitest";
import { createIdentityRuntime } from "@/core/identity";
import { ApiError } from "@/lib/api";
import {
  ControlledLocks, SESSION_ONE, SESSION_TWO, authenticationResponse, deferred, jsonResponse, problemResponse,
} from "./support";

const request = { currentPassword: "old secret", newPassword: "new long secret" };
const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function runtime(fetchImpl: typeof fetch) {
  const instance = createIdentityRuntime({ fetchImpl, locks: () => new ControlledLocks().manager });
  runtimes.push(instance);
  instance.session.accept(authenticationResponse("current-bearer"));
  return instance;
}
afterEach(() => { for (const instance of runtimes.splice(0)) instance.dispose(); });

describe("IAM password-change transport", () => {
  it("sends the exact bearer-only payload, accepts 204, and does not replace the current session", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      expect(String(path)).toBe("/api/iam/v1/auth/password");
      expect(init?.method).toBe("POST");
      expect(init?.credentials).toBe("same-origin");
      expect(init?.cache).toBe("no-store");
      expect((init?.headers as Headers).get("Authorization")).toBe("Bearer current-bearer");
      expect((init?.headers as Headers).get("Content-Type")).toBe("application/json");
      expect((init?.headers as Headers).has("X-XSRF-TOKEN")).toBe(false);
      expect(JSON.parse(String(init?.body))).toEqual(request);
      return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).resolves.toBeUndefined();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(instance.session.getAccessToken()).toBe("current-bearer");
    expect(instance.session.getSessionId()).toBe(SESSION_ONE);
  });

  it("retains structured field errors and distinct stable IAM denial codes", async () => {
    const responses = [
      problemResponse(400, "validation_failed", [{ field: "newPassword", code: "invalid", message: "Invalid new password." }]),
      problemResponse(400, "current_password_invalid"),
      problemResponse(409, "credential_changed"),
      problemResponse(403, "account_unavailable"),
      problemResponse(401, "session_unavailable"),
    ];
    const fetchImpl = vi.fn(async () => responses.shift()!);
    const instance = runtime(fetchImpl as typeof fetch);
    for (const [status, code] of [
      [400, "validation_failed"], [400, "current_password_invalid"], [409, "credential_changed"],
      [403, "account_unavailable"], [401, "session_unavailable"],
    ] as const) {
      await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).rejects.toMatchObject({ status, code } satisfies Partial<ApiError>);
    }
    expect(fetchImpl).toHaveBeenCalledTimes(5);
    expect(instance.session.getAccessToken()).toBeUndefined();
    expect(instance.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "unauthorized" });
  });

  it("refuses a password POST if auth switches between initiation and HTTP dispatch", async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const instance = runtime(fetchImpl as typeof fetch);
    const pending = instance.forSession(SESSION_ONE).security.changePassword(request);
    instance.session.accept(authenticationResponse("second-bearer", undefined, SESSION_TWO));
    await expect(pending).rejects.toMatchObject({ status: 0, code: "session_context_changed" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("never automatically refreshes or repeats a dispatched post-dispatch session denial", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(401, "session_unavailable"));
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).rejects.toMatchObject({ status: 401, code: "session_unavailable" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(instance.session.getAccessToken()).toBeUndefined();
    expect(instance.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "unauthorized" });
  });

  it.each([false, true])("classifies query-bearing password denial as terminal (replayed: %s)", async (replay) => {
    const path = "/api/iam/v1/auth/password?example=1";
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (String(input).endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (String(input).endsWith("/refresh")) return jsonResponse(authenticationResponse("fresh-bearer"));
      expect(String(input)).toBe(path);
      const bearer = (init?.headers as Headers).get("Authorization");
      return replay && bearer === "Bearer current-bearer"
        ? problemResponse(401, "invalid_credential") : problemResponse(401, "session_unavailable");
    });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).api.post(path, request)).rejects.toMatchObject({ status: 401, code: "session_unavailable" });
    expect(instance.session.getAccessToken()).toBeUndefined();
    expect(fetchImpl).toHaveBeenCalledTimes(replay ? 4 : 1);
  });

  it.each([
    ["GET", "/iam/v1/auth/password?example=1"],
    ["POST", "/groups/v1/groups?example=/api/iam/v1/auth/password"],
  ])("does not invalidate IAM for %s %s", async (method, path) => {
    const fetchImpl = vi.fn<typeof fetch>(async () => problemResponse(401, "session_unavailable"));
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).api.request(path, { method })).rejects.toMatchObject({ status: 401, code: "session_unavailable" });
    expect(instance.session.getAccessToken()).toBe("current-bearer");
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("renews only after a recognized pre-dispatch 401, then replays once on the same session", async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      const url = String(path);
      calls.push(url);
      if (url.endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (url.endsWith("/refresh")) return jsonResponse(authenticationResponse("fresh-bearer"));
      if ((init?.headers as Headers).get("Authorization") === "Bearer current-bearer") {
        return problemResponse(401, "invalid_credential");
      }
      expect((init?.headers as Headers).get("Authorization")).toBe("Bearer fresh-bearer");
      return new Response(null, { status: 204 });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).resolves.toBeUndefined();
    expect(calls).toEqual([
      "/api/iam/v1/auth/password", "/api/iam/v1/auth/csrf", "/api/iam/v1/auth/refresh", "/api/iam/v1/auth/password",
    ]);
    expect(instance.session.getAccessToken()).toBe("fresh-bearer");
  });

  it.each([SESSION_ONE, SESSION_TWO])("invalidates only the originating session on a late password session denial (%s)", async (sessionId) => {
    const reply = deferred<Response>();
    const fetchImpl = vi.fn(async () => reply.promise);
    const instance = runtime(fetchImpl as typeof fetch);
    const pending = instance.forSession(SESSION_ONE).security.changePassword(request);
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    instance.session.accept(authenticationResponse("new-bearer", undefined, sessionId));
    reply.resolve(problemResponse(401, "session_unavailable"));
    await expect(pending).rejects.toMatchObject({ code: sessionId === SESSION_ONE ? "session_unavailable" : "session_context_changed" });
    expect(instance.session.getAccessToken()).toBe(sessionId === SESSION_ONE ? undefined : "new-bearer");
    expect(instance.session.getSnapshot().status).toBe(sessionId === SESSION_ONE ? "reauthentication-required" : "authenticated");
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("treats session_unavailable on a password replay as terminal without a second refresh", async () => {
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      const url = String(path);
      if (url.endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (url.endsWith("/refresh")) return jsonResponse(authenticationResponse("fresh-bearer"));
      return (init?.headers as Headers).get("Authorization") === "Bearer current-bearer"
        ? problemResponse(401, "invalid_credential") : problemResponse(401, "session_unavailable");
    });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).rejects.toMatchObject({ status: 401, code: "session_unavailable" });
    expect(fetchImpl).toHaveBeenCalledTimes(4);
    expect(instance.session.getAccessToken()).toBeUndefined();
    expect(instance.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "unauthorized" });
  });

  it.each([
    { code: "session_unavailable", replacement: SESSION_ONE, invalidated: true },
    { code: "session_unavailable", replacement: SESSION_TWO, invalidated: false },
    { code: "invalid_credential", replacement: SESSION_ONE, invalidated: false },
  ])("distinguishes a replay's $code from a bearer-specific rejection ($replacement)", async ({ code, replacement, invalidated }) => {
    const reply = deferred<Response>();
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      const url = String(path);
      if (url.endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (url.endsWith("/refresh")) return jsonResponse(authenticationResponse("fresh-bearer"));
      return (init?.headers as Headers).get("Authorization") === "Bearer current-bearer"
        ? problemResponse(401, "invalid_credential") : reply.promise;
    });
    const instance = runtime(fetchImpl as typeof fetch);
    const pending = instance.forSession(SESSION_ONE).security.changePassword(request);
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(4));
    instance.session.accept(authenticationResponse("newer-bearer", undefined, replacement));
    reply.resolve(problemResponse(401, code));
    await expect(pending).rejects.toMatchObject({ code: replacement === SESSION_TWO ? "session_context_changed" : code });
    expect(instance.session.getAccessToken()).toBe(invalidated ? undefined : "newer-bearer");
    expect(instance.session.getSnapshot().status).toBe(invalidated ? "reauthentication-required" : "authenticated");
    expect(fetchImpl).toHaveBeenCalledTimes(4);
  });

  it("does not allow an in-flight refresh to revive a session denied by the password command", async () => {
    const passwordReply = deferred<Response>();
    const refreshReply = deferred<Response>();
    const fetchImpl = vi.fn(async (path: RequestInfo | URL) => {
      const url = String(path);
      if (url.endsWith("/password")) return passwordReply.promise;
      if (url.endsWith("/csrf")) return jsonResponse({ token: "csrf" });
      if (url.endsWith("/refresh")) return refreshReply.promise;
      return problemResponse(401, "invalid_credential");
    });
    const instance = runtime(fetchImpl as typeof fetch);
    const apis = instance.forSession(SESSION_ONE);
    const password = apis.security.changePassword(request);
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    const renewal = apis.api.get("/groups/v1/groups");
    const renewalRejection = expect(renewal).rejects.toMatchObject({ code: "session_context_changed" });
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(4));
    passwordReply.resolve(problemResponse(401, "session_unavailable"));
    await expect(password).rejects.toMatchObject({ code: "session_unavailable" });
    refreshReply.resolve(jsonResponse(authenticationResponse("late-bearer")));
    await renewalRejection;
    expect(instance.session.getAccessToken()).toBeUndefined();
    expect(instance.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "unauthorized" });
    expect(fetchImpl).toHaveBeenCalledTimes(4);
  });

  it("rejects a non-204 success response as unconfirmed and never replays it", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ ok: true }));
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).rejects.toMatchObject({ status: 0, code: "unexpected_response" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("does not repeat a POST after a lost response", async () => {
    const fetchImpl = vi.fn(async () => { throw new TypeError("connection lost"); });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.forSession(SESSION_ONE).security.changePassword(request)).rejects.toMatchObject({ status: 0, code: "network_error" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

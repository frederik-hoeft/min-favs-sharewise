import { afterEach, describe, expect, it, vi } from "vitest";
import { createIdentityRuntime } from "@/core/identity";
import { ApiError } from "@/lib/api";
import {
  ControlledLocks, SESSION_ONE, SESSION_TWO, authenticationResponse, jsonResponse, problemResponse,
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
    await expect(instance.security.changePassword(request, SESSION_ONE)).resolves.toBeUndefined();
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
      await expect(instance.security.changePassword(request, SESSION_ONE)).rejects.toMatchObject({ status, code } satisfies Partial<ApiError>);
    }
    expect(fetchImpl).toHaveBeenCalledTimes(5);
    expect(instance.session.getAccessToken()).toBe("current-bearer");
  });

  it("refuses a password POST if auth switches between initiation and HTTP dispatch", async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const instance = runtime(fetchImpl as typeof fetch);
    const pending = instance.security.changePassword(request, SESSION_ONE);
    instance.session.accept(authenticationResponse("second-bearer", undefined, SESSION_TWO));
    await expect(pending).rejects.toMatchObject({ status: 0, code: "session_context_changed" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("never automatically refreshes or repeats a dispatched post-dispatch session denial", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(401, "session_unavailable"));
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.security.changePassword(request, SESSION_ONE)).rejects.toMatchObject({ status: 401, code: "session_unavailable" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(instance.session.getAccessToken()).toBe("current-bearer");
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
    await expect(instance.security.changePassword(request, SESSION_ONE)).resolves.toBeUndefined();
    expect(calls).toEqual([
      "/api/iam/v1/auth/password", "/api/iam/v1/auth/csrf", "/api/iam/v1/auth/refresh", "/api/iam/v1/auth/password",
    ]);
    expect(instance.session.getAccessToken()).toBe("fresh-bearer");
  });

  it("rejects a non-204 success response as unconfirmed and never replays it", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ ok: true }));
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.security.changePassword(request, SESSION_ONE)).rejects.toMatchObject({ status: 0, code: "unexpected_response" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("does not repeat a POST after a lost response", async () => {
    const fetchImpl = vi.fn(async () => { throw new TypeError("connection lost"); });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.security.changePassword(request, SESSION_ONE)).rejects.toMatchObject({ status: 0, code: "network_error" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

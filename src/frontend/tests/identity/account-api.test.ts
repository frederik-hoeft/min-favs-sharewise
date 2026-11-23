import { afterEach, describe, expect, it, vi } from "vitest";
import { createIdentityRuntime } from "@/core/identity";
import { ApiError } from "@/lib/api";
import { authenticationResponse, jsonResponse, problemResponse } from "./support";

const profile = { userId: "00000000-0000-0000-0000-000000000001", email: "alice@example.com", displayName: null };
const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function createRuntime(fetchImpl: typeof fetch) {
  const runtime = createIdentityRuntime({ fetchImpl });
  runtimes.push(runtime);
  runtime.session.accept(authenticationResponse("account-bearer"));
  return runtime;
}
afterEach(() => { for (const runtime of runtimes.splice(0)) runtime.dispose(); });

describe("Typed IAM account API", () => {
  it("uses the active bearer for its own-account GET and exact PATCH bodies, without CSRF/cookie authority", async () => {
    const calls: Array<{ path: string; method: string; authorization: string | null; body: string | undefined; credentials: RequestCredentials | undefined }> = [];
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, init?: RequestInit) => {
      calls.push({
        path: String(path), method: init?.method ?? "", authorization: (init?.headers as Headers).get("Authorization"),
        body: init?.body?.toString(), credentials: init?.credentials,
      });
      expect(init?.cache).toBe("no-store");
      expect((init?.headers as Headers).has("X-XSRF-TOKEN")).toBe(false);
      return jsonResponse({ ...profile, displayName: calls.length === 2 ? "  Alice 🦊  " : null });
    });
    const runtime = createRuntime(fetchImpl as typeof fetch);
    expect(await runtime.account.getAccount()).toEqual(profile);
    expect((await runtime.account.setDisplayName("  Alice 🦊  ")).displayName).toBe("  Alice 🦊  ");
    expect((await runtime.account.clearDisplayName()).displayName).toBeNull();
    expect(calls).toEqual([
      { path: "/api/iam/v1/account", method: "GET", authorization: "Bearer account-bearer", body: undefined, credentials: "same-origin" },
      { path: "/api/iam/v1/account", method: "PATCH", authorization: "Bearer account-bearer", body: JSON.stringify({ displayName: "  Alice 🦊  ", clear: false }), credentials: "same-origin" },
      { path: "/api/iam/v1/account", method: "PATCH", authorization: "Bearer account-bearer", body: JSON.stringify({ displayName: null, clear: true }), credentials: "same-origin" },
    ]);
  });

  it("preserves structured field errors and non-validation IAM denials", async () => {
    const fields = [{ field: "displayName", code: "invalid", message: "Unsupported display name." }];
    const responses = [problemResponse(400, "validation_failed", fields), problemResponse(400, "malformed_request"), problemResponse(403, "account_unavailable")];
    const fetchImpl = vi.fn(async () => responses.shift()!);
    const runtime = createRuntime(fetchImpl as typeof fetch);
    await expect(runtime.account.setDisplayName("x")).rejects.toMatchObject({ status: 400, code: "validation_failed", fieldErrors: fields } satisfies Partial<ApiError>);
    await expect(runtime.account.clearDisplayName()).rejects.toMatchObject({ status: 400, code: "malformed_request" } satisfies Partial<ApiError>);
    await expect(runtime.account.getAccount()).rejects.toMatchObject({ status: 403, code: "account_unavailable" } satisfies Partial<ApiError>);
  });

  it("invalidates only the bearer that received a 401, never a newer session", async () => {
    let responseToFirst!: (response: Response) => void;
    const first = new Promise<Response>((resolve) => { responseToFirst = resolve; });
    let calls = 0;
    const fetchImpl = vi.fn(async () => ++calls === 1 ? first : jsonResponse(profile));
    const runtime = createRuntime(fetchImpl as typeof fetch);
    const pending = runtime.account.getAccount();
    // onRequest has attached the old bearer, but its response is still pending.
    await Promise.resolve();
    await Promise.resolve();
    runtime.session.accept(authenticationResponse("new-account-bearer"));
    responseToFirst(problemResponse(401, "invalid_token"));
    await expect(pending).rejects.toMatchObject({ status: 401 });
    expect(runtime.session.getSnapshot().status).toBe("authenticated");
    expect(runtime.session.getAccessToken()).toBe("new-account-bearer");
    expect(await runtime.account.getAccount()).toEqual(profile);
  });
  it("does not interpret an account-operation session_unavailable code as an IAM auth denial", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(401, "session_unavailable"));
    const runtime = createRuntime(fetchImpl as typeof fetch);
    await expect(runtime.account.getAccount()).rejects.toMatchObject({ status: 401, code: "session_unavailable" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(runtime.session.getAccessToken()).toBe("account-bearer");
    expect(runtime.session.getSnapshot().status).toBe("authenticated");
  });

});

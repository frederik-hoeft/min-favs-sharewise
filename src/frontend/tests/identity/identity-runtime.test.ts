import { afterEach, describe, expect, it, vi } from "vitest";
import { AUTH_COOKIE_LOCK, AuthCookieLock, CSRF_HEADER, createIdentityRuntime } from "@/core/identity";
import { ApiError } from "@/lib/api";
import {
  ControlledLocks, authenticationResponse, credentials, deferred, jsonResponse, problemResponse,
} from "./support";

const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function createRuntime(fetchImpl: typeof fetch, locks: () => LockManager | undefined) {
  const runtime = createIdentityRuntime({ fetchImpl, locks });
  runtimes.push(runtime);
  return runtime;
}
afterEach(() => { for (const runtime of runtimes.splice(0)) runtime.dispose(); });

describe("Identity runtime with mock HTTP", () => {
  it.each([
    ["register", 201, "/api/iam/v1/auth/register"],
    ["login", 200, "/api/iam/v1/auth/login"],
  ] as const)("%s performs a fresh CSRF GET under the Web Lock before the credential POST", async (operation, status, path) => {
    const locks = new ControlledLocks();
    const events: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      events.push(`${init?.method}:${url}`);
      expect(init?.credentials).toBe("same-origin");
      expect(init?.cache).toBe("no-store");
      if (url.endsWith("/csrf")) {
        expect(init?.method).toBe("GET");
        expect((init?.headers as Headers).has("Authorization")).toBe(false);
        return jsonResponse({ token: "fresh-csrf" });
      }
      expect(url).toBe(path);
      expect(init?.method).toBe("POST");
      expect((init?.headers as Headers).get(CSRF_HEADER)).toBe("fresh-csrf");
      expect((init?.headers as Headers).has("Origin")).toBe(false);
      expect((init?.headers as Headers).has("Authorization")).toBe(false);
      expect(JSON.parse(String(init?.body))).toEqual(credentials);
      return jsonResponse(authenticationResponse(), status);
    });
    const runtime = createRuntime(fetchImpl as typeof fetch, () => locks.manager);
    await runtime.auth[operation](credentials);
    expect(runtime.session.getSnapshot().status).toBe("authenticated");
    expect(runtime.session.getAccessToken()).toBe("opaque.jwt.value");
    expect(events).toEqual([
      "GET:/api/iam/v1/auth/csrf", `POST:${path}`,
    ]);
    expect(locks.events).toEqual([
      `queued:${AUTH_COOKIE_LOCK}`, `acquired:${AUTH_COOKIE_LOCK}:exclusive`, `released:${AUTH_COOKIE_LOCK}`,
    ]);
  });

  it("attaches the current bearer to subsequent domain requests, not anonymous auth requests", async () => {
    const calls: Array<{ path: string; authorization: string | null }> = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ path: String(input), authorization: (init?.headers as Headers).get("Authorization") });
      return jsonResponse({ ok: true });
    });
    const runtime = createRuntime(fetchImpl as typeof fetch, () => undefined);
    runtime.session.accept(authenticationResponse("bearer-one"));
    await runtime.api.get("/groups/v1/groups");
    await runtime.api.get("/iam/v1/auth/csrf");
    expect(calls).toEqual([
      { path: "/api/groups/v1/groups", authorization: "Bearer bearer-one" },
      { path: "/api/iam/v1/auth/csrf", authorization: null },
    ]);
  });

  it("preserves ProblemDetail validation fields and duplicate-registration code", async () => {
    const fields = [{ field: "email", code: "invalid_email", message: "Invalid email." }];
    const responses = [problemResponse(400, "validation_failed", fields), problemResponse(409, "email_unavailable")];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/csrf")
      ? jsonResponse({ token: "fresh" })
      : responses.shift()!);
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    await expect(runtime.auth.register(credentials)).rejects.toMatchObject({
      status: 400, code: "validation_failed", fieldErrors: fields,
    } satisfies Partial<ApiError>);
    await expect(runtime.auth.register(credentials)).rejects.toMatchObject({
      status: 409, code: "email_unavailable",
    } satisfies Partial<ApiError>);
    expect(runtime.session.getAccessToken()).toBeUndefined();
  });

  it("returns a generic 401 for denied login, without issuing a bearer", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/csrf")
      ? jsonResponse({ token: "fresh" })
      : problemResponse(401, "invalid_credentials"));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    await expect(runtime.auth.login(credentials)).rejects.toMatchObject({ status: 401 });
    expect(runtime.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "initial" });
  });

  it("honors an HTTP timeout override and settles the abort before releasing the lock", async () => {
    vi.useFakeTimers();
    const locks = new ControlledLocks();
    const fetchImpl = vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_, reject) => init?.signal?.addEventListener('abort', () =>
        reject(new DOMException('Aborted', 'AbortError')), { once: true })));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => locks.manager);
    try {
      const pending = runtime.api.get('/groups/v1/groups', { timeoutMs: 150 });
      const result = expect(pending).rejects.toMatchObject({ status: 0, code: 'network_error' });
      await vi.advanceTimersByTimeAsync(151);
      await result;
      expect((fetchImpl.mock.calls[0][1] as RequestInit).signal?.aborted).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("maps network failures without leaving a pending in-tab submission", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/csrf")) return jsonResponse({ token: "fresh" });
      throw new TypeError("Failed to fetch");
    });
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    for (let i = 0; i < 2; i++) {
      await expect(runtime.auth.login(credentials)).rejects.toMatchObject({ status: 0, code: "network_error" });
    }
    expect(fetchImpl).toHaveBeenCalledTimes(4);
  });

  it.each([
    ["truncated JSON", () => new Response('{"accessToken":', {
      status: 201, headers: { "content-type": "application/json" },
    })],
    ["aborted body", () => new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.error(new DOMException("body interrupted", "AbortError")); },
    }), { status: 201, headers: { "content-type": "application/json" } })],
  ])("treats committed registration with %s as uncertain without storing credentials", async (_case, response) => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/csrf")
      ? jsonResponse({ token: "fresh" }) : response());
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    await expect(runtime.auth.register(credentials)).rejects.toMatchObject({
      status: 0, code: "registration_outcome_unknown",
    });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(runtime.session.getAccessToken()).toBeUndefined();
  });

  it("rejects a malformed CSRF response without ever sending credentials", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ unexpected: "value" }));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    await expect(runtime.auth.register(credentials)).rejects.toMatchObject({ code: "invalid_csrf_response" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("aborts queued Web Lock acquisition without ever executing the mutation", async () => {
    vi.useFakeTimers();
    const action = vi.fn(async () => undefined);
    const locks = {
      request: (_name: string, options: LockOptions, _callback: (lock: Lock) => Promise<unknown>) =>
        new Promise((_, reject) => options.signal?.addEventListener('abort', () =>
          reject(new DOMException('Timed out', 'AbortError')), { once: true })),
    } as LockManager;
    const lock = new AuthCookieLock(() => locks);
    try {
      const pending = lock.runExclusive(action);
      const result = expect(pending).rejects.toMatchObject({ code: 'auth_lock_unavailable' });
      await vi.advanceTimersByTimeAsync(30_001);
      await result;
      expect(action).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("fails closed when Web Locks are absent, acquisition rejects or the callback is never entered", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ token: "unused" }));
    const cases = [
      () => undefined,
      () => ({ request: () => Promise.reject(new DOMException("Denied", "NotSupportedError")) }) as unknown as LockManager,
      () => ({ request: async () => undefined }) as unknown as LockManager,
    ];
    for (const locks of cases) {
      const runtime = createRuntime(fetchImpl as typeof fetch, locks);
      await expect(runtime.auth.login(credentials)).rejects.toMatchObject({ code: "auth_lock_unavailable" });
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("rejects a second in-tab submission without leaking or sending the second credentials", async () => {
    const pending = deferred<Response>();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/csrf")
      ? pending.promise
      : jsonResponse(authenticationResponse()));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    const first = runtime.auth.register(credentials);
    await expect(runtime.auth.login({ email: "different@example.com", password: "different" })).rejects.toMatchObject({
      code: "auth_in_progress",
    });
    pending.resolve(jsonResponse({ token: "fresh" }));
    await first;
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("serializes requests from two tab-local runtimes, including CSRF bootstrap", async () => {
    const locks = new ControlledLocks();
    const firstPost = deferred<Response>();
    const events: string[] = [];
    let csrfNumber = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/csrf")) {
        csrfNumber++;
        events.push(`csrf-${csrfNumber}`);
        return jsonResponse({ token: `csrf-${csrfNumber}` });
      }
      const header = (init?.headers as Headers).get(CSRF_HEADER);
      events.push(`post-${header}`);
      return header === "csrf-1" ? firstPost.promise : jsonResponse(authenticationResponse("second"));
    });
    const firstTab = createRuntime(fetchImpl as typeof fetch, () => locks.manager);
    const secondTab = createRuntime(fetchImpl as typeof fetch, () => locks.manager);
    const first = firstTab.auth.login(credentials);
    const second = secondTab.auth.register(credentials);
    await vi.waitFor(() => expect(events).toEqual(["csrf-1", "post-csrf-1"]));
    expect(locks.events.filter((event) => event.startsWith("acquired"))).toHaveLength(1);
    expect(csrfNumber).toBe(1);
    firstPost.resolve(jsonResponse(authenticationResponse("first")));
    await Promise.all([first, second]);
    expect(events).toEqual(["csrf-1", "post-csrf-1", "csrf-2", "post-csrf-2"]);
    expect(locks.events.filter((event) => event.startsWith("acquired"))).toHaveLength(2);
    expect(firstTab.session.getAccessToken()).toBe("first");
    expect(secondTab.session.getAccessToken()).toBe("second");
  });

  it("does not attach an expired bearer when making a subsequent API request", async () => {
    let now = Date.parse("2026-11-20T12:00:00Z");
    const observed: Array<string | null> = [];
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      observed.push((init?.headers as Headers).get("Authorization"));
      return problemResponse(401, "missing_credentials");
    });
    const runtime = createIdentityRuntime({
      fetchImpl: fetchImpl as typeof fetch,
      locks: () => undefined,
      now: () => now,
    });
    runtimes.push(runtime);
    runtime.session.accept(authenticationResponse("short-lived", "2026-11-20T12:00:01Z"));
    now += 2000;
    await expect(runtime.api.get("/groups/v1/groups")).rejects.toMatchObject({ status: 401 });
    expect(observed).toEqual([null]);
    expect(runtime.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "expired" });
  });

  it("rejects an invalid successful authentication body without storing it", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/csrf")
      ? jsonResponse({ token: "fresh" })
      : jsonResponse({ accessToken: "sensitive-opaque-value", tokenType: "Wrong", expiresAt: "invalid" }));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    await expect(runtime.auth.login(credentials)).rejects.toMatchObject({ code: "invalid_auth_response" });
    expect(runtime.session.getAccessToken()).toBeUndefined();
    expect(runtime.session.getSnapshot().status).toBe("reauthentication-required");
  });

  it("an anonymous login denial does not invalidate a previously accepted bearer", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/csrf")
      ? jsonResponse({ token: "fresh" })
      : problemResponse(401, "invalid_credentials"));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => new ControlledLocks().manager);
    runtime.session.accept(authenticationResponse("existing-token"));
    await expect(runtime.auth.login(credentials)).rejects.toMatchObject({ status: 401 });
    expect(runtime.session.getAccessToken()).toBe("existing-token");
  });

  it("a protected 401 invalidates the active bearer without retrying the request", async () => {
    const fetchImpl = vi.fn(async () => problemResponse(401, "expired_access_token"));
    const runtime = createRuntime(fetchImpl as typeof fetch, () => undefined);
    runtime.session.accept(authenticationResponse("active"));
    await expect(runtime.api.get("/groups/v1/groups")).rejects.toMatchObject({ status: 401 });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(runtime.session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "unauthorized" });
  });

  it("a delayed 401 from an old request cannot invalidate a newer login", async () => {
    const staleResponse = deferred<Response>();
    const fetchImpl = vi.fn(async () => staleResponse.promise);
    const runtime = createRuntime(fetchImpl as typeof fetch, () => undefined);
    runtime.session.accept(authenticationResponse("old-token"));
    const request = runtime.api.get("/groups/v1/groups");
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    runtime.session.accept(authenticationResponse("new-token"));
    staleResponse.resolve(problemResponse(401, "expired_access_token"));
    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(runtime.session.getAccessToken()).toBe("new-token");
  });
});

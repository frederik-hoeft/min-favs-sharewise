// @vitest-environment node

import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient, type ApiRequestHandler, type RequestHandler } from "@/lib/api";

const primary = (state: RequestHandler): ApiRequestHandler => ({ kind: 'primary', begin: () => state });
const retry = (run: () => Promise<void>) => ({ key: 'primary', run });

describe("createApiClient", () => {
  it("resolves typed JSON data on 200 OK", async () => {
    const client = createApiClient([], async (input, init) => {
      expect(input).toBe("http://sharewise.invalid/api/system/v1/health");
      expect(init?.credentials).toBe("same-origin");
      return new Response(JSON.stringify({ status: "UP" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    });

    await expect(client.get<{ status: string }>("/system/v1/health")).resolves.toEqual({ status: "UP" });
  });

  it.each(["groups/v1/groups", "/groups/v1/groups", "api/groups/v1/groups", "/api/groups/v1/groups"])(
    "classifies %s by pathname and preserves the query across replay", async (path) => {
      const query = "?search=a%2Fb%23c&flag=&search=x";
      const prepare = vi.fn();
      const recovery = vi.fn(async () => {});
      const terminal = vi.fn();
      const begin = vi.fn(() => ({ prepare, recover: () => retry(recovery), onTerminalFailure: terminal }));
      const fetchImpl = vi.fn<typeof fetch>(async () => new Response(null, { status: 401 }));
      const client = createApiClient([{ kind: 'primary', begin }], fetchImpl);
      await expect(client.get(path + query)).rejects.toMatchObject({ status: 401 });
      expect(begin).toHaveBeenCalledWith(expect.objectContaining({ pathname: '/api/groups/v1/groups', target: '/api/groups/v1/groups' + query }));
      expect(prepare).toHaveBeenCalledTimes(2);
      expect(recovery).toHaveBeenCalledTimes(1);
      expect(terminal).toHaveBeenCalledTimes(1);
      expect(fetchImpl.mock.calls.map(call => call[0])).toEqual([
        "http://sharewise.invalid/api/groups/v1/groups" + query, "http://sharewise.invalid/api/groups/v1/groups" + query,
      ]);
    },
  );

  it.each([
    "https://example.com/api/groups", "//example.com/api/groups", "/api//groups",
    "/api/iam/v1/auth/../auth/logout", "/api/iam/v1/auth/./logout",
    "/api/iam/v1/auth/%6cogout", "/api/iam/v1/auth%2flogout",
    "/api/iam/v1/auth/logout;param=x", "/api/iam/v1/auth/logout/",
    "/api/iam/v1/auth/logout#fragment", "/api/iam/v1/auth/logout?x=#fragment",
    "/api/iam/v1/auth/\\logout", "/api/iam/v1/auth/log\tout", "",
  ])("rejects noncanonical API path %j before auth or dispatch", async (path) => {
    const begin = vi.fn();
    const fetchImpl = vi.fn<typeof fetch>();
    const client = createApiClient([{ kind: 'primary', begin }], fetchImpl);
    await expect(client.get(path)).rejects.toMatchObject({ code: "invalid_api_path" });
    expect(begin).not.toHaveBeenCalled();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("resolves undefined on 204 No Content", async () => {
    const client = createApiClient([], async () => new Response(null, { status: 204 }));

    await expect(client.delete<void>("/api/items/1")).resolves.toBeUndefined();
  });

  it("parses 400 ProblemDetail with fieldErrors into ApiError", async () => {
    const client = createApiClient([], async () => new Response(
      JSON.stringify({
        type: "about:blank",
        title: "Validation Failed",
        status: 400,
        detail: "Request validation failed.",
        code: "validation_failed",
        fieldErrors: [
          { field: "amount", code: "must_be_positive", message: "must be greater than 0" },
        ],
      }),
      { status: 400, headers: { "content-type": "application/problem+json" } },
    ));

    const request = client.post("/api/expenses", { amount: 0 });
    await expect(request).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
      code: "validation_failed",
      title: "Validation Failed",
      detail: "Request validation failed.",
      fieldErrors: [{ field: "amount", code: "must_be_positive", message: "must be greater than 0" }],
    } satisfies Partial<ApiError>);
  });

  it("uses the HTTP status when ProblemDetail status differs", async () => {
    const client = createApiClient([], async () => new Response(
      JSON.stringify({
        type: "about:blank",
        title: "Forbidden",
        status: 403,
        detail: "Session expired at gateway.",
        code: "token_expired",
      }),
      { status: 401, headers: { "content-type": "application/problem+json" } },
    ));

    await expect(client.get("/api/groups")).rejects.toMatchObject({
      name: "ApiError",
      status: 401,
      code: "token_expired",
      title: "Forbidden",
      detail: "Session expired at gateway.",
    } satisfies Partial<ApiError>);
  });

  it("maps network failures to a typed ApiError", async () => {
    const client = createApiClient([], async () => {
      throw new TypeError("Failed to fetch");
    });

    await expect(client.get("/api/system/v1/health")).rejects.toMatchObject({
      name: "ApiError",
      status: 0,
      code: "network_error",
      detail: "Failed to fetch",
    } satisfies Partial<ApiError>);
  });

  it.each([
    ["truncated JSON", async () => new Response('{"accessToken":', {
      status: 201, headers: { "content-type": "application/json" },
    })],
    ["interrupted response body", async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.error(new DOMException("stream interrupted", "AbortError")); },
    }), { status: 201, headers: { "content-type": "application/json" } })],
  ])("maps a successful HTTP response with %s to an uncertain transport outcome", async (_case, response) => {
    const client = createApiClient([], async () => response());
    await expect(client.post("/iam/v1/auth/register", { email: "example@example.com" })).rejects.toMatchObject({
      name: "ApiError", status: 0, code: "invalid_response",
    } satisfies Partial<ApiError>);
  });

  it("does not classify failed HTTP responses as uncertain transport outcomes", async () => {
    const client = createApiClient([], async () => new Response("{invalid json", {
      status: 400, headers: { "content-type": "application/problem+json" },
    }));
    await expect(client.post("/iam/v1/auth/register", {})).rejects.toMatchObject({
      status: 400, code: "http_error",
    } satisfies Partial<ApiError>);
  });

  it("honors the bounded 401 retry hook without retrying indefinitely", async () => {
    const onRequest = vi.fn();
    const onUnauthorized = vi.fn(async () => {});
    const onReplayUnauthorized = vi.fn();
    const fetchImpl = vi.fn(async () => new Response(null, { status: 401 }));
    const client = createApiClient([primary({ prepare: onRequest, recover: () => retry(onUnauthorized), onTerminalFailure: onReplayUnauthorized })], fetchImpl);

    await expect(client.get("/api/groups")).rejects.toBeInstanceOf(ApiError);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(onRequest).toHaveBeenCalledTimes(2);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(onReplayUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("does not prepare authentication or fetch when the caller has already aborted", async () => {
    const onRequest = vi.fn();
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const controller = new AbortController();
    controller.abort();
    const client = createApiClient([primary({ prepare: onRequest })], fetchImpl);

    await expect(client.get("/groups", { signal: controller.signal })).rejects.toMatchObject({ code: 'request_aborted' });
    expect(onRequest).not.toHaveBeenCalled();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("does not dispatch when the caller aborts during asynchronous authentication preparation", async () => {
    let release!: () => void;
    const preparation = new Promise<void>((resolve) => { release = resolve; });
    const onRequest = vi.fn(async () => { await preparation; });
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const client = createApiClient([primary({ prepare: onRequest })], fetchImpl);
    const controller = new AbortController();

    const pending = client.get("/groups", { signal: controller.signal });
    controller.abort();
    release();
    await expect(pending).rejects.toMatchObject({ code: 'request_aborted' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("starts a fresh HTTP timeout on replay rather than timing out during renewal", async () => {
    vi.useFakeTimers();
    try {
      let attempts = 0;
      const signals: AbortSignal[] = [];
      const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        signals.push(init?.signal as AbortSignal);
        attempts++;
        return attempts === 1 ? new Response(null, { status: 401 })
          : new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      });
      const onUnauthorized = vi.fn(async () => {
        await new Promise<void>((resolve) => setTimeout(resolve, 200));
      });
      const client = createApiClient([primary({ recover: () => retry(onUnauthorized) })], fetchImpl);
      const pending = client.get<{ ok: boolean }>("/groups", { timeoutMs: 50 });
      await vi.advanceTimersByTimeAsync(210);
      await expect(pending).resolves.toEqual({ ok: true });
      expect(fetchImpl).toHaveBeenCalledTimes(2);
      expect(signals.every((signal) => !signal.aborted)).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it.each([200, 204])("rejects a stale successful %i response after the session changes", async (status) => {
    let release!: (value: Response) => void;
    const response = new Promise<Response>((resolve) => { release = resolve; });
    let currentSession = 'session-one';
    const fetchImpl = vi.fn(async () => response);
    const client = createApiClient([{ kind: 'primary', begin: () => {
      const bound = currentSession;
      return { assertCurrent: () => {
        if (bound !== currentSession) {
          throw new ApiError({ status: 0, code: 'session_context_changed', title: 'Sign-in Changed', detail: 'Session changed.' });
        }
      } };
    } }], fetchImpl);
    const pending = client.get('/groups');
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    currentSession = 'session-two';
    release(status === 204 ? new Response(null, { status }) :
      new Response(JSON.stringify({ ok: true }), { status, headers: { 'Content-Type': 'application/json' } }));
    await expect(pending).rejects.toMatchObject({ code: 'session_context_changed' });
  });

  it("replays immutable URLSearchParams with their form content type", async () => {
    let finishRenewal!: () => void;
    const renewal = new Promise<void>((resolve) => { finishRenewal = resolve; });
    const sent: Array<{ body: string; contentType: string | null }> = [];
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      sent.push({ body: String(init?.body), contentType: (init?.headers as Headers).get('Content-Type') });
      return new Response(null, { status: sent.length === 1 ? 401 : 204 });
    });
    const onUnauthorized = vi.fn(async () => renewal);
    const params = new URLSearchParams('amount=10');
    const client = createApiClient([primary({ recover: () => retry(onUnauthorized) })], fetchImpl);
    const pending = client.post('/groups', params);
    await vi.waitFor(() => expect(onUnauthorized).toHaveBeenCalledTimes(1));
    params.set('amount', '100');
    finishRenewal();
    await expect(pending).resolves.toBeUndefined();
    expect(sent).toEqual([
      { body: 'amount=10', contentType: 'application/x-www-form-urlencoded;charset=UTF-8' },
      { body: 'amount=10', contentType: 'application/x-www-form-urlencoded;charset=UTF-8' },
    ]);
  });

});

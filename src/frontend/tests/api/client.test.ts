// @vitest-environment node

import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "@/lib/api";

describe("createApiClient", () => {
  it("resolves typed JSON data on 200 OK", async () => {
    const client = createApiClient({}, async (input, init) => {
      expect(input).toBe("/api/system/v1/health");
      expect(init?.credentials).toBe("same-origin");
      return new Response(JSON.stringify({ status: "UP" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    });

    await expect(client.get<{ status: string }>("/system/v1/health")).resolves.toEqual({ status: "UP" });
  });

  it("resolves undefined on 204 No Content", async () => {
    const client = createApiClient({}, async () => new Response(null, { status: 204 }));

    await expect(client.delete<void>("/api/items/1")).resolves.toBeUndefined();
  });

  it("parses 400 ProblemDetail with fieldErrors into ApiError", async () => {
    const client = createApiClient({}, async () => new Response(
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
    const client = createApiClient({}, async () => new Response(
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
    const client = createApiClient({}, async () => {
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
    const client = createApiClient({}, async () => response());
    await expect(client.post("/iam/v1/auth/register", { email: "example@example.com" })).rejects.toMatchObject({
      name: "ApiError", status: 0, code: "invalid_response",
    } satisfies Partial<ApiError>);
  });

  it("does not classify failed HTTP responses as uncertain transport outcomes", async () => {
    const client = createApiClient({}, async () => new Response("{invalid json", {
      status: 400, headers: { "content-type": "application/problem+json" },
    }));
    await expect(client.post("/iam/v1/auth/register", {})).rejects.toMatchObject({
      status: 400, code: "http_error",
    } satisfies Partial<ApiError>);
  });

  it("honors the bounded 401 retry hook without retrying indefinitely", async () => {
    const onRequest = vi.fn();
    const onUnauthorized = vi.fn(async () => true);
    const fetchImpl = vi.fn(async () => new Response(null, { status: 401 }));
    const client = createApiClient({ onRequest, onUnauthorized }, fetchImpl);

    await expect(client.get("/api/groups")).rejects.toBeInstanceOf(ApiError);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(onRequest).toHaveBeenCalledTimes(2);
    expect(onUnauthorized).toHaveBeenCalledTimes(2);
  });
});

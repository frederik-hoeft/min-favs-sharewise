import { vi } from "vitest";
import type { GroupDetails, GroupSummary, GroupWorkspace } from "@/core/groups";
import { createApiClient, type ApiClient } from "@/lib/api";

export interface RecordedRequest {
  readonly method: string;
  readonly path: string;
  readonly body: unknown;
}

/** A scripted outcome; a promise holds the response until the test settles it. */
export type FakeResponse = Response | Error | Promise<Response | Error> | (() => Response | Error);

/**
 * A real `ApiClient` over a scripted fetch, so tests exercise ProblemDetail parsing too.
 * Responses are queued per "METHOD /path"; every request is recorded.
 */
export function scriptedClient() {
  const queues = new Map<string, FakeResponse[]>();
  const requests: RecordedRequest[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const path = String(input);
    requests.push({ method, path, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
    const next = queues.get(`${method} ${path}`)?.shift();
    if (!next) throw new Error(`Unexpected request ${method} ${path}`);
    const outcome = await (typeof next === "function" ? next() : next);
    if (outcome instanceof Error) throw outcome;
    return outcome;
  });
  const client: ApiClient = createApiClient({}, fetchImpl);
  return {
    client,
    fetchImpl: fetchImpl as unknown as typeof fetch,
    requests,
    respond(method: string, path: string, ...responses: FakeResponse[]) {
      const key = `${method} ${path}`;
      queues.set(key, [...(queues.get(key) ?? []), ...responses]);
    },
  };
}

export interface Deferred<T> {
  readonly promise: Promise<T>;
  resolve(value: T): void;
}

/** A promise the test resolves explicitly, to order asynchronous outcomes without timers. */
export function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ status, code, title: "Problem", detail: `Problem ${code}`, ...extra }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });
}

export const GROUP_ID = "0193a5c4-7e1f-7c2a-9b3d-5e6f7a8b9c0d";

export function summary(overrides: Partial<GroupSummary> = {}): GroupSummary {
  return {
    id: GROUP_ID,
    name: "Flat share",
    description: "Rent and groceries",
    memberCount: 3,
    callerRole: "admin",
    ...overrides,
  };
}

export function details(overrides: Partial<GroupDetails> = {}): GroupDetails {
  return { ...summary(), createdAt: "2026-11-20T10:15:00Z", version: 0, ...overrides };
}

export function workspace(overrides: Partial<GroupDetails> = {}): GroupWorkspace {
  const group = details(overrides);
  const members = Array.from({ length: group.memberCount }, (_, index) => ({
    id: `0193a5c4-7e1f-7c2a-9b3d-${String(index + 10).padStart(12, "0")}`,
    userId: `0193a5c4-7e1f-7c2a-9b3d-${String(index + 20).padStart(12, "0")}`,
    role: index === 0 ? group.callerRole : index === 1 && group.callerRole === "member" ? "admin" as const : "member" as const,
    joinedAt: group.createdAt,
  }));
  return { group, members, callerMembershipId: members[0].id };
}

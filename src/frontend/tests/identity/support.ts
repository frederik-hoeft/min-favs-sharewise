import type { AuthenticationResponse } from "@/core/identity";

export const credentials = { email: "person@example.com", password: "correct horse battery staple" };

export function authenticationResponse(token = "opaque.jwt.value", expiresAt = new Date(Date.now() + 300_000).toISOString()): AuthenticationResponse {
  return { accessToken: token, tokenType: "Bearer", expiresAt };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export function problemResponse(status: number, code: string, fieldErrors: unknown[] = []): Response {
  return new Response(JSON.stringify({
    type: "about:blank", title: "Request Failed", status, code, detail: "Request failed.", fieldErrors,
  }), { status, headers: { "content-type": "application/problem+json" } });
}

export function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason?: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

/** Simulates the browser's cross-tab FIFO lock ordering without relying on jsdom Web Locks. */
export class ControlledLocks {
  private tail: Promise<void> = Promise.resolve();
  readonly events: string[] = [];

  readonly manager = {
    request: <T>(name: string, options: LockOptions, callback: (lock: Lock) => Promise<T>): Promise<T> => {
      this.events.push(`queued:${name}`);
      const result = this.tail.then(async () => {
        this.events.push(`acquired:${name}:${options.mode}`);
        try {
          return await callback({ name, mode: "exclusive" } as Lock);
        } finally {
          this.events.push(`released:${name}`);
        }
      });
      this.tail = result.then(() => {}, () => {});
      return result;
    },
  } as LockManager;
}

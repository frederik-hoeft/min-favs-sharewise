import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthCookieLock, createIdentityRuntime } from '@/core/identity';
import {
  ControlledChannels, ControlledLocks, SESSION_ONE, SESSION_TWO,
  authenticationResponse, credentials, deferred, jsonResponse, problemResponse,
} from './support';

const runtimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function runtime(fetchImpl: typeof fetch, locks = new ControlledLocks(), channels?: ControlledChannels) {
  const instance = createIdentityRuntime({ fetchImpl, locks: () => locks.manager, channel: channels?.create });
  instance.connect();
  runtimes.push(instance);
  return instance;
}
afterEach(() => { for (const instance of runtimes.splice(0)) instance.dispose(); });

const DOMAIN_PATH = '/api/groups/v1/groups';
const CSRF_PATH = '/api/iam/v1/auth/csrf';
const REFRESH_PATH = '/api/iam/v1/auth/refresh';
const LOGOUT_PATH = '/api/iam/v1/auth/logout';

describe('transparent renewal and restoration', () => {
  it('renews after confirmed ingress 401, keeps cookie requests bearer-free and replays once', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const headers = init?.headers as Headers;
      calls.push(`${path}:${headers.get('Authorization') ?? 'none'}`);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) {
        expect(headers.has('Authorization')).toBe(false);
        expect(headers.get('X-XSRF-TOKEN')).toBe('csrf');
        expect(JSON.parse(String(init?.body))).toEqual({ expectedSessionId: SESSION_ONE });
        return jsonResponse(authenticationResponse('renewed'));
      }
      return headers.get('Authorization') === 'Bearer old'
        ? problemResponse(401, 'invalid_credential') : jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old', '2020-01-01T00:00:00Z'));
    await expect(instance.api.get(DOMAIN_PATH)).resolves.toEqual({ ok: true });
    expect(calls).toEqual([
      `${DOMAIN_PATH}:Bearer old`, `${CSRF_PATH}:none`, `${REFRESH_PATH}:none`, `${DOMAIN_PATH}:Bearer renewed`,
    ]);
    expect(instance.session.getSessionId()).toBe(SESSION_ONE);
  });

  it('lazily restores a reloaded tab without an expected session and uses the resulting bearer', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) {
        expect(JSON.parse(String(init?.body))).toEqual({});
        return jsonResponse(authenticationResponse('restored'));
      }
      expect((init?.headers as Headers).get('Authorization')).toBe('Bearer restored');
      return jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.api.get(DOMAIN_PATH)).resolves.toEqual({ ok: true });
    expect(calls).toEqual([CSRF_PATH, REFRESH_PATH, DOMAIN_PATH]);
    expect(instance.session.getSessionId()).toBe(SESSION_ONE);
  });

  it.each([200, 204])('discards a late successful %i response from a replaced session', async (status) => {
    const deferredResponse = deferred<Response>();
    const fetchImpl = vi.fn(async () => deferredResponse.promise);
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('alice'));
    const pending = instance.api.get(DOMAIN_PATH);
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    instance.session.accept(authenticationResponse('bob', undefined, SESSION_TWO));
    deferredResponse.resolve(status === 204 ? new Response(null, { status })
      : jsonResponse({ account: 'alice' }));
    await expect(pending).rejects.toMatchObject({ code: 'session_context_changed' });
    expect(instance.session.getAccessToken()).toBe('bob');
  });

  it('coalesces simultaneous same-tab 401s into one refresh submission', async () => {
    const refresh = deferred<Response>();
    let refreshCount = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) { refreshCount++; return refresh.promise; }
      return (init?.headers as Headers).get('Authorization') === 'Bearer old'
        ? problemResponse(401, 'invalid_credential') : jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    const both = Promise.all([instance.api.get(DOMAIN_PATH), instance.api.get(DOMAIN_PATH)]);
    await vi.waitFor(() => expect(refreshCount).toBe(1));
    refresh.resolve(jsonResponse(authenticationResponse('new')));
    await expect(both).resolves.toEqual([{ ok: true }, { ok: true }]);
    expect(refreshCount).toBe(1);
  });

  it('replays a stale 401 using a newer bearer from the same session without rotation', async () => {
    const response = deferred<Response>();
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
      (init?.headers as Headers).get('Authorization') === 'Bearer old'
        ? response.promise : jsonResponse({ ok: true }));
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    const request = instance.api.get(DOMAIN_PATH);
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    instance.session.accept(authenticationResponse('new'));
    response.resolve(problemResponse(401, 'invalid_credential'));
    await expect(request).resolves.toEqual({ ok: true });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('does not renew operation 401s, 403s or requests with non-replayable bodies', async () => {
    const fetchImpl = vi.fn(async () => problemResponse(401, 'unauthenticated'));
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse());
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'unauthenticated' });
    expect(fetchImpl).toHaveBeenCalledTimes(1);

    fetchImpl.mockImplementationOnce(async () => problemResponse(403, 'forbidden'));
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ status: 403 });
    expect(fetchImpl).toHaveBeenCalledTimes(2);

    fetchImpl.mockImplementationOnce(async () => problemResponse(401, 'invalid_credential'));
    await expect(instance.api.post(DOMAIN_PATH, new FormData())).rejects.toMatchObject({ status: 401 });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('permits one replay of a repeatable POST, then terminates on a second 401', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) return jsonResponse(authenticationResponse('new'));
      expect(init?.method).toBe('POST');
      expect(JSON.parse(String(init?.body))).toEqual({ action: 'create' });
      return problemResponse(401, 'authentication_required');
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    await expect(instance.api.post(DOMAIN_PATH, { action: 'create' })).rejects.toMatchObject({ status: 401 });
    expect(calls).toEqual([DOMAIN_PATH, CSRF_PATH, REFRESH_PATH, DOMAIN_PATH]);
    expect(instance.session.getAccessToken()).toBeUndefined();
  });

  it('preserves a newly renewed bearer when the replay returns an application-level 401', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) return jsonResponse(authenticationResponse('new'));
      return (init?.headers as Headers).get('Authorization') === 'Bearer old'
        ? problemResponse(401, 'invalid_credential')
        : problemResponse(401, 'unauthenticated');
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));

    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ status: 401, code: 'unauthenticated' });
    expect(calls).toEqual([DOMAIN_PATH, CSRF_PATH, REFRESH_PATH, DOMAIN_PATH]);
    expect(instance.session.getAccessToken()).toBe('new');
    expect(instance.session.getSnapshot().status).toBe('authenticated');
  });

  it.each([
    ['network loss', async () => { throw new TypeError('lost response'); }],
    ['malformed success', async () => jsonResponse({ accessToken: 'secret', tokenType: 'Bearer', expiresAt: 'bad' })],
    ['truncated success', async () => new Response('broken', { status: 200, headers: { 'content-type': 'application/json' } })],
  ])('does not resubmit a refresh with %s', async (_case, result) => {
    let refreshCount = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) { refreshCount++; return result(); }
      return problemResponse(401, 'invalid_credential');
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'refresh_outcome_unknown' });
    expect(refreshCount).toBe(1);
    expect(instance.session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'uncertain' });
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'reauthentication_required' });
    expect(refreshCount).toBe(1);
  });
});

describe('renewal failure and caller cancellation', () => {
  it('recovers from a transient Web Lock acquisition failure without submitting refresh', async () => {
    const locks = new ControlledLocks();
    let available = false;
    const requests: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      requests.push(path);
      return path === CSRF_PATH ? jsonResponse({ token: 'csrf' })
        : path === REFRESH_PATH ? jsonResponse(authenticationResponse('restored'))
        : jsonResponse({ ok: true });
    });
    const instance = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch, locks: () => available ? locks.manager : undefined });
    runtimes.push(instance);
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'auth_lock_unavailable' });
    expect(instance.session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'retryable' });
    expect(requests).toEqual([]);
    available = true;
    await expect(instance.api.get(DOMAIN_PATH)).resolves.toEqual({ ok: true });
    expect(requests).toEqual([CSRF_PATH, REFRESH_PATH, DOMAIN_PATH]);
  });

  it('allows a later restoration after the first CSRF bootstrap fails before submission', async () => {
    let csrfCount = 0;
    let refreshCount = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === CSRF_PATH) {
        csrfCount++;
        if (csrfCount === 1) throw new TypeError('Temporary CSRF connection failure');
        return jsonResponse({ token: 'csrf' });
      }
      if (path === REFRESH_PATH) {
        refreshCount++;
        return jsonResponse(authenticationResponse('restored'));
      }
      return jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'network_error' });
    expect(instance.session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'retryable' });
    expect(refreshCount).toBe(0);
    await expect(instance.api.get(DOMAIN_PATH)).resolves.toEqual({ ok: true });
    expect(refreshCount).toBe(1);
  });

  it('preserves the established session after failed renewal preparation and retries on the next request', async () => {
    let csrfCount = 0;
    let refreshCount = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === CSRF_PATH) {
        csrfCount++;
        if (csrfCount === 1) throw new TypeError('Temporary CSRF failure');
        return jsonResponse({ token: 'csrf' });
      }
      if (path === REFRESH_PATH) {
        refreshCount++;
        return jsonResponse(authenticationResponse('new'));
      }
      return (init?.headers as Headers).get('Authorization') === 'Bearer old'
        ? problemResponse(401, 'invalid_credential') : jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'network_error' });
    expect(instance.session.getSessionId()).toBe(SESSION_ONE);
    expect(instance.session.getAccessToken()).toBe('old');
    await expect(instance.api.get(DOMAIN_PATH)).resolves.toEqual({ ok: true });
    expect(refreshCount).toBe(1);
  });

  it.each([502, 503])('marks a submitted refresh with gateway %i as uncertain without resubmission', async (status) => {
    let rotations = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) { rotations++; return problemResponse(status, 'bad_gateway'); }
      return problemResponse(401, 'invalid_credential');
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'refresh_outcome_unknown' });
    expect(instance.session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'uncertain' });
    await expect(instance.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'reauthentication_required' });
    expect(rotations).toBe(1);
  });

  it('aborts a queued restoration before CSRF or refresh POST is submitted, then permits a later restore', async () => {
    const locks = new ControlledLocks();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const blocker = new AuthCookieLock(() => locks.manager).runExclusive(async () => gate);
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      calls.push(path);
      return path === CSRF_PATH ? jsonResponse({ token: 'csrf' })
        : path === REFRESH_PATH ? jsonResponse(authenticationResponse('new'))
        : jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch, locks);
    const controller = new AbortController();
    const pending = instance.api.get(DOMAIN_PATH, { signal: controller.signal });
    await vi.waitFor(() => expect(locks.events.filter((event) => event.startsWith('queued:'))).toHaveLength(2));
    controller.abort();
    await expect(pending).rejects.toMatchObject({ code: 'request_aborted' });
    release();
    await blocker;
    await vi.waitFor(() => expect(instance.session.getSnapshot().status).toBe('reauthentication-required'));
    expect(calls).toEqual([]);
    await expect(instance.api.get(DOMAIN_PATH)).resolves.toEqual({ ok: true });
    expect(calls).toEqual([CSRF_PATH, REFRESH_PATH, DOMAIN_PATH]);
  });

  it('aborts a pending CSRF GET after acquiring the lock when the last waiter cancels', async () => {
    const locks = new ControlledLocks();
    const calls: string[] = [];
    let firstCsrfStarted = false;
    let firstCsrfAborted = false;
    let csrfCount = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) {
        csrfCount++;
        if (csrfCount === 1) {
          firstCsrfStarted = true;
          return new Promise<Response>((_resolve, reject) => {
            const cancel = () => {
              firstCsrfAborted = true;
              reject(new DOMException('CSRF bootstrap canceled', 'AbortError'));
            };
            if (init?.signal?.aborted) cancel();
            else init?.signal?.addEventListener('abort', cancel, { once: true });
          });
        }
        return jsonResponse({ token: 'csrf' });
      }
      if (path === '/api/iam/v1/auth/login') return jsonResponse(authenticationResponse('new'));
      if (path === REFRESH_PATH) throw new Error('Canceled restoration must not submit refresh');
      throw new Error(`Unexpected request ${path}`);
    });
    const a = runtime(fetchImpl as typeof fetch, locks);
    const b = runtime(fetchImpl as typeof fetch, locks);
    const cancel = new AbortController();
    const abandoned = a.api.get(DOMAIN_PATH, { signal: cancel.signal });
    await vi.waitFor(() => expect(firstCsrfStarted).toBe(true));
    const login = b.auth.login(credentials);
    await vi.waitFor(() => expect(locks.events.filter((event) => event.startsWith('queued:'))).toHaveLength(2));
    cancel.abort();
    await expect(abandoned).rejects.toMatchObject({ code: 'request_aborted' });
    await expect(login).resolves.toBeUndefined();
    expect(firstCsrfAborted).toBe(true);
    expect(a.session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'retryable' });
    expect(b.session.getAccessToken()).toBe('new');
    expect(calls).toEqual([CSRF_PATH, CSRF_PATH, '/api/iam/v1/auth/login']);
    expect(locks.events.filter((event) => event.startsWith('acquired:'))).toHaveLength(2);
  });

  it('finishes a submitted shared refresh under the lock despite one canceled caller', async () => {
    const refresh = deferred<Response>();
    let submitted = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) { submitted++; return refresh.promise; }
      return (init?.headers as Headers).get('Authorization') === 'Bearer old'
        ? problemResponse(401, 'invalid_credential') : jsonResponse({ ok: true });
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    const controller = new AbortController();
    const first = instance.api.get(DOMAIN_PATH, { signal: controller.signal });
    const second = instance.api.get(DOMAIN_PATH);
    await vi.waitFor(() => expect(submitted).toBe(1));
    controller.abort();
    await expect(first).rejects.toMatchObject({ code: 'request_aborted' });
    refresh.resolve(jsonResponse(authenticationResponse('new')));
    await expect(second).resolves.toEqual({ ok: true });
    expect(submitted).toBe(1);
    expect(instance.session.getAccessToken()).toBe('new');
  });
});

describe('shared-cookie session contexts', () => {
  it('allows two tabs to rotate the shared cookie sequentially for one stable session', async () => {
    const locks = new ControlledLocks();
    let generation = 1;
    let rotations = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) {
        expect(JSON.parse(String(init?.body))).toEqual({ expectedSessionId: SESSION_ONE });
        rotations++;
        generation++;
        return jsonResponse(authenticationResponse(`access-${generation}`));
      }
      const bearer = (init?.headers as Headers).get('Authorization');
      return bearer === 'Bearer expired' ? problemResponse(401, 'invalid_credential') : jsonResponse({ ok: true });
    });
    const a = runtime(fetchImpl as typeof fetch, locks);
    const b = runtime(fetchImpl as typeof fetch, locks);
    a.session.accept(authenticationResponse('expired'));
    b.session.accept(authenticationResponse('expired'));
    await expect(Promise.all([a.api.get(DOMAIN_PATH), b.api.get(DOMAIN_PATH)]))
      .resolves.toEqual([{ ok: true }, { ok: true }]);
    expect(rotations).toBe(2);
    expect(a.session.getSessionId()).toBe(SESSION_ONE);
    expect(b.session.getSessionId()).toBe(SESSION_ONE);
    expect(a.session.getSnapshot().status).toBe('authenticated');
    expect(b.session.getSnapshot().status).toBe('authenticated');
    expect(locks.events.filter((event) => event.startsWith('acquired'))).toHaveLength(2);
  });

  it('broadcasts a different login context and blocks stale-tab requests', async () => {
    const channels = new ControlledChannels();
    const locks = new ControlledLocks();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
      ? jsonResponse({ token: 'csrf' }) : jsonResponse(authenticationResponse('bob', undefined, SESSION_TWO)));
    const alice = runtime(fetchImpl as typeof fetch, locks, channels);
    const bob = runtime(fetchImpl as typeof fetch, locks, channels);
    alice.session.accept(authenticationResponse('alice'));
    await bob.auth.login(credentials);
    expect(alice.session.getSnapshot()).toEqual({ status: 'context-changed' });
    expect(alice.session.getAccessToken()).toBeUndefined();
    await expect(alice.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'session_context_changed' });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('detects a missed broadcast through the authoritative expected-session check', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === REFRESH_PATH) {
        expect(JSON.parse(String(init?.body))).toEqual({ expectedSessionId: SESSION_ONE });
        return problemResponse(409, 'session_context_changed');
      }
      return problemResponse(401, 'invalid_credential');
    });
    const alice = runtime(fetchImpl as typeof fetch);
    alice.session.accept(authenticationResponse('alice'));
    await expect(alice.api.get(DOMAIN_PATH)).rejects.toMatchObject({ code: 'session_context_changed' });
    expect(alice.session.getSnapshot()).toEqual({ status: 'context-changed' });
    expect(calls).toEqual([DOMAIN_PATH, CSRF_PATH, REFRESH_PATH]);
  });

  it('broadcasts logout to matching tabs without attempting refresh or clearing a different context', async () => {
    const channels = new ControlledChannels();
    const locks = new ControlledLocks();
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === LOGOUT_PATH) {
        expect(JSON.parse(String(init?.body))).toEqual({ expectedSessionId: SESSION_ONE });
        return new Response(null, { status: 204 });
      }
      throw Error('unexpected request');
    });
    const a = runtime(fetchImpl as typeof fetch, locks, channels);
    const b = runtime(fetchImpl as typeof fetch, locks, channels);
    a.session.accept(authenticationResponse('token-a'));
    b.session.accept(authenticationResponse('token-b'));
    await a.auth.logout();
    expect(a.session.getSessionId()).toBeUndefined();
    expect(b.session.getSessionId()).toBeUndefined();
    expect(b.session.getAccessToken()).toBeUndefined();
    expect(calls).toEqual([CSRF_PATH, LOGOUT_PATH]);
  });

  it('does not clear or revoke a replacement session on mismatched logout', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      calls.push(path);
      if (path === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (path === LOGOUT_PATH) {
        expect(JSON.parse(String(init?.body))).toEqual({ expectedSessionId: SESSION_ONE });
        return problemResponse(409, 'session_context_changed');
      }
      throw Error('unexpected request');
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('old'));
    await expect(instance.auth.logout()).rejects.toMatchObject({ code: 'session_context_changed' });
    expect(instance.session.getSnapshot()).toEqual({ status: 'context-changed' });
    expect(calls).toEqual([CSRF_PATH, LOGOUT_PATH]);
  });

  it('does not silently adopt a different session between renewal and original-request replay', async () => {
    const refresh = deferred<Response>();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === CSRF_PATH) return jsonResponse({ token: 'csrf' });
      if (String(input) === REFRESH_PATH) return refresh.promise;
      return problemResponse(401, 'invalid_credential');
    });
    const instance = runtime(fetchImpl as typeof fetch);
    instance.session.accept(authenticationResponse('alice'));
    const request = instance.api.get(DOMAIN_PATH);
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(3));
    instance.session.accept(authenticationResponse('bob', new Date().toISOString(), SESSION_TWO));
    refresh.resolve(jsonResponse(authenticationResponse('renewed')));
    await expect(request).rejects.toMatchObject({ code: 'session_context_changed' });
    expect(instance.session.getAccessToken()).toBe('bob');
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });
});

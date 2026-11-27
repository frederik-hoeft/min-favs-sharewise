import { afterEach, describe, expect, it, vi } from 'vitest';
import { createIdentityRuntime, type IdentityRuntime } from '@/core/identity';
import { ProfileResolver } from '@/core/identity/profile-resolver';
import { authenticationResponse, deferred, jsonResponse, SESSION_ONE } from './support';

const a = '00000000-0000-0000-0000-000000000001';
const b = '00000000-0000-0000-0000-000000000002';
const runtimes: IdentityRuntime[] = [];
afterEach(() => { for (const runtime of runtimes.splice(0)) runtime.dispose(); });

describe('session-local profile resolver', () => {
    it('coalesces overlapping lookups, bounds network batches and returns all results despite eviction', async () => {
        const fetch = vi.fn(async (ids: readonly string[]) => ids.map(userId => ({ userId, displayName: userId })));
        const resolver = new ProfileResolver(fetch, () => undefined);
        const ids = Array.from({ length: 600 }, (_, i) => String(i));
        const [first, second] = await Promise.all([resolver.resolve([...ids, '0']), resolver.resolve(['0', '1'])]);
        expect(first.size).toBe(600);
        expect(second.get('0')).toEqual({ status: 'resolved', displayName: '0' });
        expect(fetch).toHaveBeenCalledTimes(6);
        expect(fetch.mock.calls.every(([batch]) => batch.length === 100)).toBe(true);
        await resolver.resolve(['599']);
        expect(fetch).toHaveBeenCalledTimes(6);
        await resolver.resolve(['0']);
        expect(fetch).toHaveBeenCalledTimes(7);
        resolver.close();
    });

    it('uses independent positive and negative TTLs; failures are not missing and preserve stale names', async () => {
        let now = 0;
        let unavailable = false;
        const fetch = vi.fn(async (ids: readonly string[]) => {
            if (unavailable) throw new Error('offline');
            return ids.filter(id => id === a).map(userId => ({ userId, displayName: 'Alice' }));
        });
        const resolver = new ProfileResolver(fetch, () => undefined, () => now);
        expect((await resolver.resolve([a, b])).get(b)).toEqual({ status: 'missing' });
        now = 9_999;
        await resolver.resolve([a, b]);
        expect(fetch).toHaveBeenCalledTimes(1);
        now = 10_000;
        await resolver.resolve([a, b]);
        expect(fetch.mock.calls[1][0]).toEqual([b]);
        now = 60_000;
        unavailable = true;
        expect((await resolver.resolve([a, b])).get(a)).toEqual({ status: 'unavailable', displayName: 'Alice' });
        unavailable = false;
        expect((await resolver.resolve([a])).get(a)).toEqual({ status: 'resolved', displayName: 'Alice' });
        resolver.close();
    });

    it('does not abort another waiter and discards an older response after rename', async () => {
        const response = deferred<readonly { userId: string; displayName: string }[]>();
        const fetch = vi.fn(async () => response.promise);
        const resolver = new ProfileResolver(fetch, () => undefined);
        const abort = new AbortController();
        const canceled = resolver.resolve([a], abort.signal);
        const surviving = resolver.resolve([a]);
        abort.abort();
        await expect(canceled).rejects.toMatchObject({ code: 'request_aborted' });
        resolver.remember({ userId: a, displayName: 'Renamed' });
        expect((await surviving).get(a)).toEqual({ status: 'resolved', displayName: 'Renamed' });
        response.resolve([{ userId: a, displayName: 'Old' }]);
        await response.promise;
        expect((await resolver.resolve([a])).get(a)).toEqual({ status: 'resolved', displayName: 'Renamed' });
        expect(fetch).toHaveBeenCalledTimes(1);
        resolver.close();
    });

    it('shares across facades and refresh, closes on replacement even with the same session ID', async () => {
        const pending = deferred<Response>();
        const fetch = vi.fn<typeof globalThis.fetch>(async (_input, init) => {
            const ids = JSON.parse(String(init?.body)).userIds as string[];
            if (ids.includes(b)) return pending.promise;
            return jsonResponse({ profiles: [{ userId: a, displayName: 'Alice' }] });
        });
        const runtime = createIdentityRuntime({ fetchImpl: fetch });
        runtimes.push(runtime);
        runtime.session.accept(authenticationResponse('old'));
        const first = runtime.forSession(SESSION_ONE).profiles;
        await first.resolve([a]);
        runtime.session.accept(authenticationResponse('refreshed'));
        await runtime.forSession(SESSION_ONE).profiles.resolve([a]);
        expect(fetch).toHaveBeenCalledTimes(1);
        const late = first.resolve([b]);
        await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
        runtime.session.invalidate('unauthorized');
        runtime.session.accept(authenticationResponse('replacement'));
        await expect(late).rejects.toMatchObject({ code: 'session_context_changed' });
        pending.resolve(jsonResponse({ profiles: [{ userId: b, displayName: 'Old' }] }));
        await expect(first.resolve([a])).rejects.toMatchObject({ code: 'session_context_changed' });
        await runtime.forSession(SESSION_ONE).profiles.resolve([a]);
        expect(fetch).toHaveBeenCalledTimes(3);
    });
});

describe('profile lookup transport', () => {
    it.each([
        { profiles: [{ userId: b, displayName: 'Foreign' }] },
        { profiles: [{ userId: a, displayName: 'Alice' }, { userId: a, displayName: 'Duplicate' }] },
        { profiles: [{ userId: a, displayName: null }] },
    ])('treats malformed results as unavailable, not missing', async ({ profiles }) => {
        const fetch = vi.fn(async () => jsonResponse({ profiles }));
        const runtime = createIdentityRuntime({ fetchImpl: fetch });
        runtimes.push(runtime);
        runtime.session.accept(authenticationResponse());
        const resolver = runtime.forSession(SESSION_ONE).profiles;
        expect((await resolver.resolve([a])).get(a)?.status).toBe('unavailable');
        await resolver.resolve([a]);
        expect(fetch).toHaveBeenCalledTimes(2);
    });
});

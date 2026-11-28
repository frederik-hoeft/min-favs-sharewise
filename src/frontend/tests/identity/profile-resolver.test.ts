import { afterEach, describe, expect, it, vi } from 'vitest';
import { createIdentityRuntime, type IdentityRuntime } from '@/core/identity';
import { ProfileResolver } from '@/core/identity/profile-resolver';
import { authenticationResponse, deferred, jsonResponse, SESSION_ONE } from './support';

const a = '00000000-0000-0000-0000-000000000001';
const b = '00000000-0000-0000-0000-000000000002';
const runtimes: IdentityRuntime[] = [];
afterEach(() => { for (const runtime of runtimes.splice(0)) runtime.dispose(); });

describe('stateless profile resolver', () => {
    it('deduplicates input, bounds batches, returns all results and always fetches anew', async () => {
        const fetch = vi.fn(async (ids: readonly string[]) => ids.map(userId => ({ userId, displayName: userId })));
        const resolver = new ProfileResolver(fetch, () => undefined);
        const ids = Array.from({ length: 600 }, (_, i) => String(i));
        expect((await resolver.resolve([...ids, '0'])).size).toBe(600);
        expect(fetch).toHaveBeenCalledTimes(6);
        expect(fetch.mock.calls.every(([batch]) => batch.length === 100)).toBe(true);
        await resolver.resolve(['599']);
        expect(fetch).toHaveBeenCalledTimes(7);
        expect((await resolver.resolve([])).size).toBe(0);
        expect(fetch).toHaveBeenCalledTimes(7);
    });

    it('distinguishes missing from unavailable and continues after one failed batch', async () => {
        const ids = Array.from({ length: 201 }, (_, i) => String(i));
        const fetch = vi.fn(async (batch: readonly string[]) => {
            if (batch.includes('100')) throw new Error('offline');
            return batch.filter(id => id !== '0').map(userId => ({ userId, displayName: userId }));
        });
        const resolver = new ProfileResolver(fetch, () => undefined);
        const result = await resolver.resolve(ids);
        expect(result.size).toBe(201);
        expect(result.get('0')).toEqual({ status: 'missing' });
        expect(result.get('100')).toEqual({ status: 'unavailable' });
        expect(result.get('200')).toEqual({ status: 'resolved', displayName: '200' });
    });

    it('cancels the owned batch, skips later batches and does not block unrelated work', async () => {
        const first = deferred<readonly { userId: string; displayName: string }[]>();
        const fetch = vi.fn(async (batch: readonly string[], signal?: AbortSignal) => {
            if (batch.includes('0')) {
                expect(signal).toBe(abort.signal);
                return first.promise;
            }
            return batch.map(userId => ({ userId, displayName: userId }));
        });
        const resolver = new ProfileResolver(fetch, () => undefined);
        const abort = new AbortController();
        const canceled = resolver.resolve(Array.from({ length: 300 }, (_, i) => String(i)), abort.signal);
        abort.abort();
        expect((await resolver.resolve([b])).get(b)).toEqual({ status: 'resolved', displayName: b });
        first.resolve([]);
        await expect(canceled).rejects.toMatchObject({ code: 'request_aborted' });
        expect(fetch).toHaveBeenCalledTimes(2);
    });

    it('refetches through facades after renewal and rejects old lifetime responses after replacement', async () => {
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
        expect(fetch).toHaveBeenCalledTimes(2);
        const late = first.resolve([b]);
        await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
        runtime.session.invalidate('unauthorized');
        runtime.session.accept(authenticationResponse('replacement'));
        pending.resolve(jsonResponse({ profiles: [{ userId: b, displayName: 'Old' }] }));
        await expect(late).rejects.toMatchObject({ code: 'session_context_changed' });
        await expect(first.resolve([a])).rejects.toMatchObject({ code: 'session_context_changed' });
        await runtime.forSession(SESSION_ONE).profiles.resolve([a]);
        expect(fetch).toHaveBeenCalledTimes(4);
    });
});

describe('profile lookup transport', () => {
    it('trusts backend name policy rather than revalidating returned strings', async () => {
        const displayName = '🌍'.repeat(81);
        const runtime = createIdentityRuntime({ fetchImpl: vi.fn(async () => jsonResponse({ profiles: [{ userId: a, displayName }] })) });
        runtimes.push(runtime);
        runtime.session.accept(authenticationResponse());
        expect((await runtime.forSession(SESSION_ONE).profiles.resolve([a])).get(a)).toEqual({ status: 'resolved', displayName });
    });
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

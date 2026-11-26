import { randomUUID } from 'node:crypto';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createIdentityRuntime, type IdentityRuntime } from '@/core/identity';
import { type ApiClient } from '@/lib/api';
import { ControlledLocks, deferred } from '../identity/support.ts';
import { DeployedHttpsTransport, type Trace } from './https-transport.ts';

const ROOT = '/smoke/v1';
const GROUPS = '/groups/v1';
const ISSUANCE = '/api/groups/v1/domain-principal/attestations';
const PRIMARY = '/api/iam/v1/auth/refresh';

class Fixture {
    readonly transport = new DeployedHttpsTransport();
    readonly credentials = { email: `authorization-${randomUUID()}@example.invalid`, password: randomUUID() + randomUUID() };
    readonly runtime: IdentityRuntime;
    readonly api: ApiClient;
    offset = 0;
    x!: string;
    y!: string;
    constructor(options: { credentialCapacity?: number; requirementBucketCapacity?: number } = {}) {
        const locks = new ControlledLocks();
        this.runtime = createIdentityRuntime({ origin: this.transport.origin, fetchImpl: this.transport.fetch,
            locks: () => locks.manager, channel: () => undefined, attestationCaches: { now: () => Date.now() + this.offset, ...options } });
        this.api = this.runtime.api;
    }
    async setup(): Promise<void> {
        await this.runtime.auth.register(this.credentials);
        await this.api.post<void>(`${ROOT}/seed-user`, undefined, { expectedStatus: 204 });
        this.x = (await this.api.post<{ id: string }>(`${GROUPS}/groups`, { name: 'Authorization X' }, { expectedStatus: 201 })).id;
        this.y = (await this.api.post<{ id: string }>(`${GROUPS}/groups`, { name: 'Authorization Y' }, { expectedStatus: 201 })).id;
        this.transport.traces.length = 0;
    }
    get traces(): Trace[] { return this.transport.traces; }
    async step(action: () => Promise<unknown>, statuses: readonly number[]): Promise<Trace[]> {
        const from = this.traces.length;
        await action();
        const trace = this.traces.slice(from);
        expect(trace.map(item => item.status)).toEqual(statuses);
        return trace;
    }
    async count(): Promise<number> { return (await this.api.get<{ effects: number }>(`${ROOT}/effects`)).effects; }
    path(group = this.x): string { return `${ROOT}/path/${group}`; }
    read(group = this.x): Promise<unknown> { return this.api.get(this.path(group)); }
    mutate(group = this.x): Promise<unknown> { return this.api.post(this.path(group), {}); }
    async dispose(): Promise<void> {
        try { if (this.runtime.session.getSnapshot().status === 'authenticated') await this.runtime.auth.logout(); }
        finally { this.runtime.dispose(); }
    }
}

const fixtures: Fixture[] = [];
async function create(options?: ConstructorParameters<typeof Fixture>[0]): Promise<Fixture> {
    const fixture = new Fixture(options);
    fixtures.push(fixture);
    await fixture.setup();
    return fixture;
}
afterEach(async () => {
    for (const fixture of fixtures.splice(0)) await fixture.dispose();
});

describe('deployed distributed authorization (real HTTPS, real IAM/Group authority)', () => {
    it('cold/warm/cross-endpoint, exact URI and shared CC; lazy expiry without new affinity', async () => {
        const f = await create();
        const cold = await f.step(() => f.read(), [401, 200, 200]);
        expect(cold.map(item => [item.method, item.target, item.code ?? null])).toEqual([
            ['GET', `/api${f.path()}`, 'attestation_required'], ['POST', ISSUANCE, null], ['GET', `/api${f.path()}`, null],
        ]);
        expect(cold[1].acquisition).toEqual({ authority: 'groups', kind: 'membership', scope: f.x });
        expect(cold.map(item => item.credentials.length)).toEqual([0, 0, 1]);
        const token = cold[2].credentials;
        expect((await f.step(() => f.read(), [200]))[0].credentials).toEqual(token);
        expect((await f.step(() => f.api.get(`${ROOT}/other/${f.x}`), [401, 200]))[1].credentials).toEqual(token);
        expect((await f.step(() => f.api.get(`${ROOT}/other/${f.x}`), [200]))[0].credentials).toEqual(token);
        // Request-affine includes all query parameters; method keys are independent.
        await f.step(() => f.api.get(`${ROOT}/request/${f.x}?timestamp=1`), [401, 200]);
        await f.step(() => f.api.get(`${ROOT}/request/${f.x}?timestamp=1`), [200]);
        await f.step(() => f.api.get(`${ROOT}/request/${f.x}?timestamp=2`), [401, 200]);
        const before = await f.count();
        await f.step(() => f.mutate(), [401, 200]);
        expect(await f.count()).toBe(before + 1);
        // Advance only the advisory client clock to omit the cached JWT; reset
        // on the actual 401 so the next real issuer response remains usable.
        f.offset = 121_000;
        f.transport.afterResponse = async trace => { if (trace.code === 'attestation_required') f.offset = 0; };
        const renewed = await f.step(() => f.read(), [401, 200, 200]);
        f.transport.afterResponse = undefined;
        expect(renewed[0].credentials).toHaveLength(0);
        expect(renewed[2].credentials).not.toEqual(token);
        expect((await f.step(() => f.api.get(`${ROOT}/other/${f.x}`), [200]))[0].credentials).toEqual(renewed[2].credentials);
    });

    it('query projection, missing/empty/repeated selectors and body-dependent never', async () => {
        const f = await create();
        const query = (group: string, suffix: string) => f.api.get(`${ROOT}/query?target=${group}${suffix}`);
        await f.step(() => query(f.x, '&timestamp=1'), [401, 200, 200]);
        await f.step(() => query(f.x, '&timestamp=2'), [200]);
        await f.step(() => query(f.y, '&timestamp=2'), [401, 200, 200]);
        for (const variant of ['&variant=', '&variant', '&variant=a&variant=b', '&variant=b&variant=a']) {
            await f.step(() => query(f.x, variant), [401, 200]);
            await f.step(() => query(f.x, `${variant}&timestamp=3`), [200]);
        }
        await f.step(() => query(f.x, ''), [200]);
        const before = await f.count();
        for (const group of [f.x, f.y, f.x]) {
            const trace = await f.step(() => f.api.post(`${ROOT}/body`, { target: group, timestamp: Date.now() }), [401, 200]);
            expect(trace[0].credentials).toHaveLength(0);
        }
        expect(await f.count()).toBe(before + 3);
    });

    it('multiple authorities, distinct requirement identities, deduplication and one effect per recovery', async () => {
        const f = await create();
        const transfer = (destination: string) => f.api.post(`${ROOT}/transfer/${f.x}?destination=${destination}`, {});
        const before = await f.count();
        const first = await f.step(() => transfer(f.y), [401, 200, 401, 200, 401, 200, 200]);
        expect(first.filter(item => item.acquisition).map(item => item.acquisition?.authority)).toEqual(['groups', 'groups', 'smoke']);
        expect(first.filter(item => !item.acquisition).map(item => item.credentials.length)).toEqual([0, 1, 2, 3]);
        expect(first.filter(item => item.acquisition).every(item => item.credentials.length === 0)).toBe(true);
        expect(await f.count()).toBe(before + 1);
        const warm = await f.step(() => transfer(f.y), [401, 200]);
        expect(warm.map(item => item.credentials.length)).toEqual([2, 3]);
        // Source and destination now share a single logical credential. The
        // backend still checks both, but the client must transmit it only once.
        const same = await f.step(() => transfer(f.x), [401, 200]);
        expect(same.map(item => item.credentials.length)).toEqual([1, 2]);
        expect(await f.count()).toBe(before + 3);
    });

    it('replaces an obsolete association without evicting credentials referenced by another endpoint', async () => {
        const f = await create();
        const initial = await f.step(() => f.read(), [401, 200, 200]);
        const x = initial[2].credentials;
        const dynamic = (target: string) => f.api.post(`${ROOT}/dynamic`, { target, timestamp: Date.now() });
        await f.step(() => dynamic(f.x), [401, 200]);
        const changed = await f.step(() => dynamic(f.y), [401, 200, 200]);
        expect(changed[0].credentials).toEqual(x);
        expect(changed[2].credentials).not.toEqual(x);
        expect((await f.step(() => f.read(), [200]))[0].credentials).toEqual(x);
        expect((await f.step(() => dynamic(f.y), [200]))[0].credentials).toEqual(changed[2].credentials);
    });

    it('real indexed signature rejection and real IAM refresh preserve unrelated references', async () => {
        const f = await create();
        await f.step(() => f.read(), [401, 200, 200]);
        const other = await f.step(() => f.read(f.y), [401, 200, 200]);
        f.transport.corruptNextSupplementary = true;
        const recovered = await f.step(() => f.read(), [401, 401, 200, 200]);
        expect(recovered[0].code).toBe('invalid_attestation');
        expect(recovered[1].code).toBe('attestation_required');
        expect((await f.step(() => f.read(f.y), [200]))[0].credentials).toEqual(other[2].credentials);
        const state = f.runtime.session.getSnapshot();
        if (state.status !== 'authenticated') throw new Error('Expected authenticated session');
        // Deliberately invalidate the access JWT while retaining the real refresh
        // cookie and session. The actual ingress rejects it, then IAM rotates.
        f.runtime.session.accept({ accessToken: 'invalid.jwt.signature', tokenType: 'Bearer',
            expiresAt: state.expiresAt, sessionId: state.sessionId });
        const refreshed = await f.step(() => f.read(), [401, 200, 200, 200]);
        expect(refreshed.some(item => item.target === PRIMARY)).toBe(true);
        expect(refreshed[3].credentials).toEqual(recovered[3].credentials);
        expect(f.runtime.session.getSessionId()).toBe(state.sessionId);
    });

    it('single-flight issuance and independent canceled waiters, with real issuer response', async () => {
        const f = await create();
        const arrived = deferred<void>();
        const release = deferred<void>();
        f.transport.afterResponse = async trace => {
            if (trace.target === ISSUANCE) { arrived.resolve(); await release.promise; }
        };
        const canceled = new AbortController();
        const first = f.api.get(f.path(), { signal: canceled.signal }).catch(error => error);
        const second = f.api.get(`${ROOT}/other/${f.x}`);
        try {
            await arrived.promise;
            await vi.waitFor(() => expect(f.traces.filter(item => item.code === 'attestation_required')).toHaveLength(2));
            canceled.abort();
            expect(await first).toMatchObject({ code: 'request_aborted' });
        } finally { release.resolve(); }
        await second;
        f.transport.afterResponse = undefined;
        expect(f.traces.filter(item => item.target === ISSUANCE)).toHaveLength(1);
        expect(f.traces.filter(item => item.target.endsWith(`/other/${f.x}`)).map(item => item.status)).toEqual([401, 200]);
        await f.step(() => f.read(), [401, 200]); // canceled RC association was not committed
    });

    it('logout/re-login replaces the lifetime and suppresses late publication', async () => {
        const f = await create();
        const arrived = deferred<void>();
        const release = deferred<void>();
        f.transport.afterResponse = async trace => {
            if (trace.target === ISSUANCE) { arrived.resolve(); await release.promise; }
        };
        const original = f.runtime.session.getSessionId();
        const pending = f.read().catch(error => error);
        try {
            await arrived.promise;
            await f.runtime.auth.logout();
            await f.runtime.auth.login(f.credentials);
            expect(f.runtime.session.getSessionId()).not.toBe(original);
            expect(await pending).toMatchObject({ code: 'session_context_changed' });
        } finally { release.resolve(); f.transport.afterResponse = undefined; }
        const next = await f.step(() => f.read(), [401, 200, 200]);
        expect(next[0].credentials).toHaveLength(0);
    });

    it('denied, unavailable, repeated and recursive challenges do not execute effects', async () => {
        const f = await create();
        const before = await f.count();
        await f.step(async () => {
            await expect(f.mutate(randomUUID())).rejects.toMatchObject({ status: 404, code: 'group_not_found', detail: 'Group membership evidence is required.' });
        }, [401, 404]);
        for (const [kind, status, expected] of [['unavailable', 503, [401, 503]], ['recursive', 401, [401, 401]], ['repeat', 401, [401, 200, 401]]] as const) {
            await f.step(async () => {
                await expect(f.api.post(`${ROOT}/failure/${kind}`, {})).rejects.toMatchObject({ status });
            }, expected);
        }
        expect(await f.count()).toBe(before);
        expect(f.runtime.session.getSnapshot().status).toBe('authenticated');
    });

    it('committed membership removal denies fresh issuance without rewriting old time-bounded evidence', async () => {
        const owner = await create();
        const guest = await create();
        const groupPath = `${GROUPS}/groups/${owner.x}`;
        const invitation = await owner.api.post<{ token: string }>(`${groupPath}/invitations`, { maxUses: 1 }, { expectedStatus: 201 });
        await guest.api.post(`${GROUPS}/invitations/redeem`, { token: invitation.token });
        const workspace = await guest.api.get<{ callerMembershipId: string }>(`${groupPath}/workspace`);
        await guest.step(() => guest.read(owner.x), [401, 200, 200]);
        await owner.api.delete(`${groupPath}/members/${workspace.callerMembershipId}`, { expectedStatus: 204 });
        // The asynchronous consumer may accept the previously issued evidence
        // until its expiry. The authority must reject a new issuance now.
        await guest.step(() => guest.read(owner.x), [200]);
        guest.offset = 121_000;
        guest.transport.afterResponse = async trace => { if (trace.code === 'attestation_required') guest.offset = 0; };
        const before = await guest.count();
        await guest.step(async () => {
            await expect(guest.mutate(owner.x)).rejects.toMatchObject({ status: 404, code: 'group_not_found' });
        }, [401, 404]);
        expect(await guest.count()).toBe(before);
    });

    it('proxy and backend enforce supplementary bounds without recovery for explicit credentials', async () => {
        const f = await create();
        const token = f.runtime.session.getAccessToken();
        if (!token) throw new Error('Expected authenticated session');
        const before = await f.count();
        for (const header of ['a.b.c, a.b.c', 'a.b.c, ', 'a'.repeat(2048) + '.b.c', Array(5).fill('a.b.c').join(', ')]) {
            await f.step(async () => {
                await expect(f.api.post(f.path(), {}, { headers: { Authorization: `Bearer ${token}`, 'ShareWise-Attestation': header } }))
                    .rejects.toMatchObject({ status: 401, code: 'invalid_attestation' });
            }, [401]);
        }
        expect(await f.count()).toBe(before);
    });

    it('non-replayable and uncertain mutation outcomes never trigger blind replay', async () => {
        const f = await create();
        const before = await f.count();
        const form = new FormData();
        form.set('test', 'non-replayable');
        await f.step(async () => { await expect(f.api.post(f.path(), form)).rejects.toMatchObject({ status: 401 }); }, [401]);
        expect(await f.count()).toBe(before);
        await f.step(() => f.mutate(), [401, 200, 200]);
        expect(await f.count()).toBe(before + 1);
        f.transport.loseNextSuccess = true;
        await f.step(async () => { await expect(f.mutate()).rejects.toMatchObject({ code: 'network_error' }); }, [200]);
        expect(await f.count()).toBe(before + 2);
    });

    it('both caches disabled preserve authorization and exactly-once effect semantics', async () => {
        const f = await create({ credentialCapacity: 0, requirementBucketCapacity: 0 });
        const before = await f.count();
        await f.step(() => f.mutate(), [401, 200, 200]);
        await f.step(() => f.mutate(), [401, 200, 200]);
        expect(await f.count()).toBe(before + 2);
    });
});

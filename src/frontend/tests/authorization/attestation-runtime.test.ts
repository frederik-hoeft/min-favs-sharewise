// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createIdentityRuntime, type IdentityRuntime } from '@/core/identity';
import type { AttestationCacheOptions } from '@/core/authorization/attestation-handler';
import { ATTESTATION_HEADER, MAX_ATTESTATION_HEADER_LENGTH, type AffinityPolicy, type CredentialDescriptor } from '@/lib/api/authorization-protocol';
import { authenticationResponse, ControlledLocks, deferred, jsonResponse, problemResponse, SESSION_TWO } from '../identity/support';

const ORIGIN = 'https://sharewise.test';
const NOW = Date.parse('2026-11-26T12:00:00Z');
const path = { policy: 'path_affine' } as const;
const never = { policy: 'never' } as const;
const query = { policy: 'query_affine', parameters: ['target'] } as const;
const descriptor = (scope: string, authority = 'groups', kind = 'membership') => ({ authority, kind, scope });
const key = (d: CredentialDescriptor) => JSON.stringify([d.authority, d.kind, d.scope]);
interface Need { requirementId: string; credential: CredentialDescriptor; cache: AffinityPolicy }
const need = (scope: string, cache: AffinityPolicy = path, id = 'member', authority = 'groups', kind = 'membership'): Need =>
    ({ requirementId: id, credential: descriptor(scope, authority, kind), cache });
const challenge = (n: Need) => jsonResponse({ code: 'attestation_required', detail: 'Membership is required.', authorization: n }, 401);
const invalid = (index?: number) => jsonResponse({ code: 'invalid_attestation', authorization: index === undefined ? undefined : { rejectedIndex: index } }, 401);

interface Trace { method: string; target: string; credentials: string[]; bearer: string | null; body: unknown }
interface BusinessRequest { target: URL; method: string; body: unknown; trace: Trace }
const runtimes: IdentityRuntime[] = [];
afterEach(() => { for (const runtime of runtimes.splice(0)) runtime.dispose(); });

/** A deterministic protocol peer: only the real frontend runtime performs acquisition and recovery. */
class Fixture {
    now = NOW;
    effects = 0;
    readonly traces: Trace[] = [];
    readonly issued = new Map<string, { key: string; expiresAt: number }>();
    readonly rejected = new Set<string>();
    tokenLength = 0;
    issueOverride?: (d: CredentialDescriptor, request: Trace, issue: () => Response) => Promise<Response> | Response;
    businessOverride?: (request: BusinessRequest, normal: () => Response) => Promise<Response> | Response;
    needs: (request: BusinessRequest) => Need[] = () => [need('X')];
    readonly runtime: IdentityRuntime;

    constructor(options: AttestationCacheOptions = {}) {
        this.runtime = createIdentityRuntime({ origin: ORIGIN, fetchImpl: this.fetch, locks: () => new ControlledLocks().manager,
            attestationCaches: { now: () => this.now, ...options } });
        runtimes.push(this.runtime);
        this.runtime.session.accept(authenticationResponse('primary'));
    }

    readonly fetch: typeof fetch = async (input, init) => {
        const target = new URL(String(input), ORIGIN);
        const headers = new Headers(init?.headers);
        const raw = headers.get(ATTESTATION_HEADER);
        expect(raw?.length ?? 0).toBeLessThanOrEqual(MAX_ATTESTATION_HEADER_LENGTH);
        const tokens = raw?.split(', ') ?? [];
        expect(new Set(tokens).size).toBe(tokens.length);
        expect(tokens.length).toBeLessThanOrEqual(4);
        const body = typeof init?.body === 'string' ? JSON.parse(init.body) as unknown : init?.body;
        const trace = { target: target.pathname + target.search, method: init?.method ?? 'GET', body,
            bearer: headers.get('Authorization'), credentials: tokens.map(token => this.issued.get(token)?.key ?? 'unknown') };
        this.traces.push(trace);
        const authority = target.pathname.match(/^\/api\/([^/]+)\/v1\/domain-principal\/attestations$/)?.[1];
        if (authority) {
            expect(tokens).toEqual([]);
            expect(trace.bearer?.startsWith('Bearer ')).toBe(true);
            expect(trace.method).toBe('POST');
            const d = { authority, ...(body as { kind: string; scope: string }) };
            const issue = () => {
                const stem = `header.payload.sig${this.issued.size + 1}`;
                const token = stem.padEnd(Math.max(stem.length, this.tokenLength), 'x');
                const expiresAt = this.now + 120_000;
                this.issued.set(token, { key: key(d), expiresAt });
                return jsonResponse({ attestation: token, expiresAt: new Date(expiresAt).toISOString() });
            };
            return this.issueOverride ? this.issueOverride(d, trace, issue) : issue();
        }
        if (target.pathname.endsWith('/auth/csrf')) { expect(tokens).toEqual([]); return jsonResponse({ token: 'csrf' }); }
        if (target.pathname.endsWith('/auth/refresh')) { expect(tokens).toEqual([]); return jsonResponse(authenticationResponse('renewed')); }
        const request = { target, method: trace.method, body, trace };
        const normal = () => {
            const rejectedIndex = tokens.findIndex(token => !this.issued.has(token) || this.rejected.has(token) || this.issued.get(token)!.expiresAt <= this.now);
            if (rejectedIndex >= 0) return invalid(rejectedIndex);
            const missing = this.needs(request).find(n => !trace.credentials.includes(key(n.credential)));
            if (missing) return challenge(missing);
            if (trace.method !== 'GET') this.effects++;
            return jsonResponse({ ok: true });
        };
        return this.businessOverride ? this.businessOverride(request, normal) : normal();
    };

    get issuanceCount() { return this.traces.filter(trace => trace.target.endsWith('/domain-principal/attestations')).length; }
    get api() { return this.runtime.api; }
    async get(target: string, signal?: AbortSignal): Promise<number> {
        const before = this.traces.length;
        await expect(this.api.get(target, { signal })).resolves.toEqual({ ok: true });
        return this.traces.length - before;
    }
    async post(target: string, body: unknown = {}): Promise<number> {
        const before = this.traces.length;
        await expect(this.api.post(target, body)).resolves.toEqual({ ok: true });
        return this.traces.length - before;
    }
}

describe('generic attestation handler and independent caches', () => {
    it('has three cold, one warm and two cross-endpoint requests; one CC entry serves both RC entries', async () => {
        const f = new Fixture();
        expect(await f.get('/finance/v1/groups/X/expenses')).toBe(3);
        expect(await f.get('/finance/v1/groups/X/expenses')).toBe(1);
        expect(await f.get('/finance/v1/groups/X/balance')).toBe(2);
        expect(await f.get('/finance/v1/groups/X/balance')).toBe(1);
        expect(f.issuanceCount).toBe(1);
        f.now += 120_001;
        expect(await f.get('/finance/v1/groups/X/expenses')).toBe(3);
        expect(await f.get('/finance/v1/groups/X/balance')).toBe(1);
        expect(f.issuanceCount).toBe(2);
    });

    it('checks CC expiry on a cold method affinity before attempting fresh issuance', async () => {
        let clientOffset: number = 0;
        const f: Fixture = new Fixture({ now: (): number => NOW + clientOffset });
        const target: string = '/finance/v1/groups/X/expenses';
        expect(await f.get(target)).toBe(3);
        // The authority no longer issues membership evidence, but previously
        // issued JWTs are still valid according to its independent clock.
        f.issueOverride = (): Response => {
            clientOffset = 0;
            return problemResponse(404, 'group_not_found');
        };
        expect(await f.get(target)).toBe(1);
        clientOffset = 121_000;
        const before: number = f.traces.length;
        await expect(f.api.post(target, {})).rejects.toMatchObject({ status: 404, code: 'group_not_found' });
        expect(f.traces.slice(before).map((trace: Trace): string => trace.target)).toEqual([
            `/api${target}`, '/api/groups/v1/domain-principal/attestations',
        ]);
        expect(f.issuanceCount).toBe(2);
        expect(f.effects).toBe(0);
    });

    it('projects selected query values, not timestamps, and never derives a scope from the path', async () => {
        const f = new Fixture();
        f.needs = ({ target }) => [need(target.searchParams.get('target')!, query)];
        expect(await f.post('/finance/v1/operation?target=X&timestamp=1')).toBe(3);
        expect(await f.post('/finance/v1/operation?timestamp=2&target=X')).toBe(1);
        expect(await f.post('/finance/v1/operation?target=Y&timestamp=3')).toBe(3);
        expect(await f.post('/finance/v1/operation?target=X&timestamp=4')).toBe(1);
        expect(await f.get('/finance/v1/operation?target=X&timestamp=4')).toBe(2);
        expect(f.effects).toBe(4);
    });

    it('distinguishes missing, empty, bare, repeated and ordered selected query fields', async () => {
        const f = new Fixture();
        f.needs = () => [need('X', query)];
        for (const suffix of ['', '?target=', '?target', '?target=X&target=Y', '?target=Y&target=X']) {
            expect(await f.get('/finance/v1/query' + suffix)).toBe(suffix === '' ? 3 : 2);
            expect(await f.get('/finance/v1/query' + suffix)).toBe(1);
        }
        // Malformed percent/UTF-8 and ambiguous delimiters do not create an affinity.
        for (const suffix of ['?target=%ZZ', '?target=%FF', '?target=X;target=Y', '?target=X&&timestamp=1']) {
            expect(await f.get('/finance/v1/query' + suffix)).toBe(2);
            expect(await f.get('/finance/v1/query' + suffix)).toBe(2);
        }
        expect(f.issuanceCount).toBe(1);
    });

    it('request affinity uses the entire query and never-affinity ignores bodies without losing credentials', async () => {
        const f = new Fixture();
        f.needs = () => [need('X', { policy: 'request_affine' })];
        expect(await f.get('/finance/v1/request?timestamp=1')).toBe(3);
        expect(await f.get('/finance/v1/request?timestamp=2')).toBe(2);
        expect(await f.get('/finance/v1/request?timestamp=1')).toBe(1);
        f.needs = ({ body }) => [need((body as { target: string }).target, never)];
        expect(await f.post('/finance/v1/body', { target: 'X', timestamp: 1 })).toBe(2);
        expect(await f.post('/finance/v1/body', { target: 'Y', timestamp: 2 })).toBe(3);
        expect(await f.post('/finance/v1/body', { target: 'X', timestamp: 3 })).toBe(2);
        expect(f.issuanceCount).toBe(2);
    });

    it('accumulates independent requirements from multiple authorities, deduplicating only credentials', async () => {
        const f = new Fixture();
        f.needs = ({ target }) => [need('X', path, 'source'), need(target.searchParams.get('target')!, query, 'destination'),
            need('Z', never, 'owner', 'files', 'ownership')];
        expect(await f.post('/finance/v1/transfer?target=Y')).toBe(7);
        expect(f.effects).toBe(1);
        expect(await f.post('/finance/v1/transfer?target=Y&timestamp=1')).toBe(2);
        expect(f.traces.at(-1)?.credentials).toEqual(expect.arrayContaining([key(descriptor('X')), key(descriptor('Y')), key(descriptor('Z', 'files', 'ownership'))]));
        expect(await f.post('/finance/v1/transfer?target=X')).toBe(2);
        expect(f.traces.at(-1)?.credentials).toHaveLength(2);
        expect(f.effects).toBe(3);
        expect(f.issuanceCount).toBe(3);
    });

    it('replaces an obsolete association while preserving other requirements and shared CC entries', async () => {
        const f = new Fixture();
        let destination = 'X';
        f.needs = ({ target }) => target.pathname.endsWith('/other') ? [need('X')] :
            [need('W', path, 'source'), need(destination, path, 'destination')];
        expect(await f.post('/finance/v1/transfer')).toBe(5);
        expect(await f.get('/finance/v1/other')).toBe(2);
        destination = 'Y';
        expect(await f.post('/finance/v1/transfer')).toBe(3);
        expect(f.traces.at(-1)?.credentials).toEqual(expect.arrayContaining([key(descriptor('W')), key(descriptor('Y'))]));
        expect(f.traces.at(-1)?.credentials).not.toContain(key(descriptor('X')));
        expect(await f.get('/finance/v1/other')).toBe(1);
        expect(f.issuanceCount).toBe(3);
    });

    it('never commits an affinity after unsuccessful replay and honors changed never policy', async () => {
        const f = new Fixture();
        f.businessOverride = (request, normal) => request.trace.credentials.length ? problemResponse(403, 'insufficient_permissions') : normal();
        await expect(f.api.get('/finance/v1/item')).rejects.toMatchObject({ status: 403 });
        f.businessOverride = undefined;
        expect(await f.get('/finance/v1/item')).toBe(2);
        f.needs = () => [need('Y', never)];
        expect(await f.get('/finance/v1/item')).toBe(3);
        expect(await f.get('/finance/v1/item')).toBe(2);
        expect(f.traces.at(-2)?.credentials).toEqual([]);
    });

    it('honors a terminal never-affine challenge without replay or evicting the credential', async () => {
        const f = new Fixture();
        const endpoint = '/finance/v1/item';
        expect(await f.get(endpoint)).toBe(3);
        f.businessOverride = () => challenge(need('X', never));
        const before = f.traces.length;
        await expect(f.api.get(endpoint)).rejects.toMatchObject({ status: 401 });
        expect(f.traces.length - before).toBe(1);
        f.businessOverride = undefined;
        expect(await f.get(endpoint)).toBe(2);
        expect(f.traces.at(-2)?.credentials).toEqual([]);
        expect(f.issuanceCount).toBe(1);
    });

    it('does not let late successful replay overwrite a newer association', async () => {
        const f = new Fixture();
        const delayed = deferred<Response>();
        f.needs = ({ body }) => [need((body as { target: string }).target)];
        f.businessOverride = (request, normal) => (request.body as { target: string }).target === 'X' && request.trace.credentials.length
            ? delayed.promise : normal();
        const older = f.api.post('/finance/v1/operation', { target: 'X' });
        await vi.waitFor(() => expect(f.traces).toHaveLength(3));
        expect(await f.post('/finance/v1/operation', { target: 'Y' })).toBe(3);
        delayed.resolve(jsonResponse({ ok: true }));
        await expect(older).resolves.toEqual({ ok: true });
        expect(await f.post('/finance/v1/operation', { target: 'Y' })).toBe(1);
    });

    it('keeps already-required credentials even when the small CC evicts them; caches can be disabled', async () => {
        const f = new Fixture({ credentialCapacity: 1 });
        f.needs = () => [need('X', path, 'a'), need('Y', path, 'b'), need('Z', path, 'c')];
        expect(await f.post('/finance/v1/operation')).toBe(7);
        expect(f.traces.at(-1)?.credentials).toHaveLength(3);
        expect(f.effects).toBe(1);
        const disabled = new Fixture({ credentialCapacity: 0, requirementBucketCapacity: 0 });
        disabled.needs = f.needs;
        expect(await disabled.post('/finance/v1/operation')).toBe(7);
        expect(await disabled.post('/finance/v1/operation')).toBe(7);
        expect(disabled.effects).toBe(2);
        expect(disabled.issuanceCount).toBe(6);
    });

    it('bounds cache pressure and prioritizes newly required evidence over optional predictions', async () => {
        const f = new Fixture({ requirementBucketCapacity: 1 });
        expect(await f.get('/finance/v1/first')).toBe(3);
        expect(await f.get('/finance/v1/second')).toBe(2);
        expect(await f.get('/finance/v1/first')).toBe(2);
        expect(f.issuanceCount).toBe(1);
        f.tokenLength = 2048;
        let selected = 'A';
        f.needs = () => [need(selected, path, selected)];
        // Build independent learned requirements; the fourth long token cannot fit alongside all three predictions.
        for (const id of ['A', 'B', 'C', 'D']) { selected = id; await f.post('/finance/v1/pressure'); }
        expect(f.traces.at(-1)?.credentials).toContain(key(descriptor('D')));
        expect(f.traces.at(-1)?.credentials.length).toBeLessThanOrEqual(3);
    });

    it('refreshes IAM in a primary-only detour and preserves both caches across bearer rotation', async () => {
        const f = new Fixture();
        f.issueOverride = (_d, trace, issue) => trace.bearer === 'Bearer primary' ? problemResponse(401, 'invalid_credential') : issue();
        expect(await f.get('/finance/v1/first')).toBe(6); // challenge, issue rejection, csrf, refresh, issue, replay
        expect(f.runtime.session.getAccessToken()).toBe('renewed');
        expect(await f.get('/finance/v1/first')).toBe(1);
        f.runtime.session.accept(authenticationResponse('next-bearer'));
        expect(await f.get('/finance/v1/first')).toBe(1);
        expect(f.traces.at(-1)?.bearer).toBe('Bearer next-bearer');
        f.runtime.session.accept(authenticationResponse('replacement', undefined, SESSION_TWO));
        f.issueOverride = undefined;
        expect(await f.get('/finance/v1/first')).toBe(3);
    });

    it('shares one issuance flight; canceled waiter does not abort another waiter', async () => {
        const f = new Fixture();
        const gate = deferred<void>();
        f.issueOverride = async (_d, _trace, issue) => { await gate.promise; return issue(); };
        const controller = new AbortController();
        const a = f.api.get('/finance/v1/a', { signal: controller.signal });
        const b = f.api.get('/finance/v1/b');
        await vi.waitFor(() => expect(f.traces).toHaveLength(3));
        controller.abort();
        await expect(a).rejects.toMatchObject({ code: 'request_aborted' });
        gate.resolve();
        await expect(b).resolves.toEqual({ ok: true });
        expect(f.issuanceCount).toBe(1);
        expect(await f.get('/finance/v1/b')).toBe(1);
        expect(await f.get('/finance/v1/a')).toBe(2);
    });

    it.each(['cancel', 'replace'])('suppresses late publication after %s', async mode => {
        const f = new Fixture();
        {
            const gate = deferred<void>();
            f.issueOverride = async (_d, _trace, issue) => { await gate.promise; return issue(); };
            const controller = new AbortController();
            const before = f.issuanceCount;
            const pending = f.api.get('/finance/v1/' + mode, { signal: controller.signal });
            await vi.waitFor(() => expect(f.issuanceCount).toBe(before + 1));
            if (mode === 'cancel') controller.abort();
            else { f.runtime.session.invalidate('unauthorized'); f.runtime.session.accept(authenticationResponse('replacement')); }
            await expect(pending).rejects.toMatchObject({ code: mode === 'cancel' ? 'request_aborted' : 'session_context_changed' });
            gate.resolve();
            f.issueOverride = undefined;
            expect(await f.get('/finance/v1/' + mode)).toBe(3);
        }
    });

    it('indexed rejection removes exactly the rejected generation, never a newer CC entry', async () => {
        const f = new Fixture();
        expect(await f.get('/finance/v1/first')).toBe(3);
        expect(await f.get('/finance/v1/second')).toBe(2);
        const old = [...f.issued.keys()][0];
        const delayed = deferred<Response>();
        let hold = true;
        f.businessOverride = (request, normal) => {
            if (hold && request.target.pathname.endsWith('/first')) { hold = false; return delayed.promise; }
            return normal();
        };
        const older = f.api.get('/finance/v1/first');
        await vi.waitFor(() => expect(hold).toBe(false));
        f.rejected.add(old);
        expect(await f.get('/finance/v1/second')).toBe(4); // reject, missing, issue, replay
        delayed.resolve(invalid(0));
        await expect(older).resolves.toEqual({ ok: true });
        expect(f.issuanceCount).toBe(2);
        expect(await f.get('/finance/v1/first')).toBe(1);
    });

    it.each([403, 404, 503])('preserves denial detail/cause and distinguishes authority failure (%i)', async status => {
        const f = new Fixture();
        f.issueOverride = () => problemResponse(status, 'authority_result');
        const error = await f.api.post('/finance/v1/operation', {}).catch(error => error);
        expect(error).toMatchObject({ status, code: 'authority_result', detail: status === 503 ? 'Request failed.' : 'Membership is required.' });
        if (status !== 503) expect(error).toMatchObject({ cause: { status, code: 'authority_result' } });
        expect(f.effects).toBe(0);
        expect(f.traces).toHaveLength(2);
        expect(f.runtime.session.getAccessToken()).toBe('primary');
    });

    it.each(['same', 'other-id', 'unindexed', 'wrong-index', 'recursive', 'arbitrary'])('terminates %s challenges without unchanged or recursive replay', async scenario => {
        const f = new Fixture();
        if (scenario === 'recursive') f.issueOverride = () => challenge(need('X'));
        if (scenario === 'arbitrary') f.businessOverride = () => problemResponse(401, 'business_denial');
        if (['same', 'other-id', 'unindexed', 'wrong-index'].includes(scenario)) {
            f.businessOverride = (request, normal) => !request.trace.credentials.length ? normal() : scenario === 'unindexed' ? invalid()
                : scenario === 'wrong-index' ? invalid(3) : challenge(need('X', path, scenario === 'same' ? 'member' : 'another'));
        }
        await expect(f.api.post('/finance/v1/operation', {})).rejects.toMatchObject({ status: 401 });
        expect(f.effects).toBe(0);
        expect(f.issuanceCount).toBe(scenario === 'arbitrary' ? 0 : 1);
        expect(f.traces.length).toBeLessThanOrEqual(3);
    });
});

// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { createApiClient, type ApiRequestHandler } from '@/lib/api';

const denial = (code: string) => new Response(JSON.stringify({ code }), {
    status: 401, headers: { 'Content-Type': 'application/problem+json' },
});
const ok = () => new Response(null, { status: 204 });

function handlers() {
    let bearer = 'first';
    const refresh = vi.fn(async () => { bearer = 'renewed'; });
    const acquire = vi.fn();
    const primary: ApiRequestHandler = {
        kind: 'primary', begin: request => request.pathname === '/api/anonymous' ? undefined : ({
            prepare: headers => headers.set('Authorization', 'Bearer ' + bearer),
            recover: failure => failure.code === 'primary' ? { key: 'primary', run: refresh } : undefined,
        }),
    };
    const supplementary: ApiRequestHandler = {
        kind: 'supplementary', begin: () => {
            const accumulated: string[] = [];
            return {
                prepare(headers) { if (accumulated.length) headers.set('Supplementary', accumulated.join(',')); },
                recover(failure) {
                    if (!failure.code.startsWith('need:')) return;
                    const scope = failure.code.slice(5);
                    return { key: scope, async run(primaryOnly) {
                        acquire(scope);
                        await primaryOnly.post('/authority/issue', { scope });
                        accumulated.push(scope);
                    } };
                },
            };
        },
    };
    return { pipeline: [primary, supplementary], refresh, acquire };
}

const header = (init: RequestInit | undefined, key: string) => new Headers(init?.headers).get(key);

describe('logical request recovery pipeline', () => {
    it('shares one primary recovery and three supplementary actions with primary-only detours', async () => {
        const { pipeline, refresh, acquire } = handlers();
        const fetchImpl = vi.fn<typeof fetch>(async (path, init) => {
            if (path === '/api/authority/issue') {
                expect(header(init, 'Supplementary')).toBeNull();
                return header(init, 'Authorization') === 'Bearer first' ? denial('primary') : ok();
            }
            expect(path).toBe('/api/business?target=x&stamp=1');
            expect(init?.method).toBe('POST');
            expect(init?.body).toBe('{"value":42}');
            const presented = header(init, 'Supplementary');
            return presented === 'a,b,c' ? ok() : denial('need:' + (presented === 'a,b' ? 'c' : presented === 'a' ? 'b' : 'a'));
        });
        await expect(createApiClient(pipeline, fetchImpl).post('/business?target=x&stamp=1', { value: 42 })).resolves.toBeUndefined();
        expect(refresh).toHaveBeenCalledTimes(1);
        expect(acquire.mock.calls).toEqual([['a'], ['b'], ['c']]);
        expect(fetchImpl.mock.calls.filter(call => call[0] === '/api/business?target=x&stamp=1')).toHaveLength(4);
        expect(fetchImpl.mock.calls.filter(call => call[0] === '/api/authority/issue')).toHaveLength(4);
    });

    it('cannot reset the primary budget through a detour or a replay', async () => {
        const { pipeline, refresh, acquire } = handlers();
        const fetchImpl = vi.fn<typeof fetch>(async (path, init) => path === '/api/authority/issue'
            ? denial('primary') : header(init, 'Authorization') === 'Bearer first' ? denial('primary') : denial('need:a'));
        await expect(createApiClient(pipeline, fetchImpl).post('/business', {})).rejects.toMatchObject({ code: 'primary' });
        expect(refresh).toHaveBeenCalledTimes(1);
        expect(acquire).toHaveBeenCalledTimes(1);
        expect(fetchImpl).toHaveBeenCalledTimes(3);
    });

    it.each(['repeat', 'fourth', 'recursive'])('terminates %s supplementary recovery', async (scenario) => {
        const { pipeline, acquire } = handlers();
        let count = 0;
        const fetchImpl = vi.fn<typeof fetch>(async path => {
            if (path === '/api/authority/issue') return scenario === 'recursive' ? denial('need:z') : ok();
            return denial('need:' + (scenario === 'repeat' ? 'a' : ++count));
        });
        await expect(createApiClient(pipeline, fetchImpl).post('/business', {})).rejects.toMatchObject({ status: 401 });
        expect(acquire).toHaveBeenCalledTimes(scenario === 'fourth' ? 3 : 1);
        expect(fetchImpl).toHaveBeenCalledTimes(scenario === 'fourth' ? 7 : scenario === 'repeat' ? 3 : 2);
    });

    it.each(['anonymous', 'explicit', 'non-replayable', 'uncertain'])('does not recover %s requests', async scenario => {
        const { pipeline, refresh, acquire } = handlers();
        const fetchImpl = vi.fn<typeof fetch>(async (_path, init) => {
            if (scenario === 'explicit') expect(header(init, 'Authorization')).toBe('Bearer caller');
            if (scenario === 'anonymous') expect(header(init, 'Authorization')).toBeNull();
            if (scenario === 'uncertain') throw new TypeError('lost response');
            return denial('need:a');
        });
        const client = createApiClient(pipeline, fetchImpl);
        await expect(client.post(scenario === 'anonymous' ? '/anonymous' : '/business',
            scenario === 'non-replayable' ? new FormData() : {},
            scenario === 'explicit' ? { headers: { Authorization: 'Bearer caller' } } : {})).rejects.toBeDefined();
        expect(refresh).not.toHaveBeenCalled();
        expect(acquire).not.toHaveBeenCalled();
        expect(fetchImpl).toHaveBeenCalledTimes(1);
    });

    it('uses the canonical full target and rejects redirected/mismatched responses before recovery', async () => {
        const { pipeline, acquire } = handlers();
        const response = denial('need:a');
        Object.defineProperty(response, 'url', { value: 'https://other.invalid/api/business' });
        const fetchImpl = vi.fn<typeof fetch>(async (_path, init) => {
            expect(init?.redirect).toBe('error');
            return response;
        });
        const client = createApiClient(pipeline, fetchImpl, 'https://example.test');
        await expect(client.get('/business?q=a b', { redirect: 'follow' })).rejects.toMatchObject({ code: 'invalid_response' });
        expect(fetchImpl.mock.calls[0]?.[0]).toBe('/api/business?q=a%20b');
        expect(acquire).not.toHaveBeenCalled();
    });
});

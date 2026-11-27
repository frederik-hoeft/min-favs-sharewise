import { describe, expect, it, vi } from 'vitest';
import { createApiClient, type ApiRequestHandler } from '@/lib/api';

const denial = (): Response => new Response(JSON.stringify({ code: 'need' }), {
    status: 401, headers: { 'Content-Type': 'application/problem+json' },
});

describe('credential-bearing request destinations', () => {
    it('uses the validated absolute origin on every attempt and primary-only detour despite a foreign document base', async () => {
        const base: HTMLBaseElement = document.createElement('base');
        base.href = 'https://other.invalid/';
        document.head.append(base);
        try {
            const origin: string = location.origin;
            let acquired: boolean = false;
            const pipeline: ApiRequestHandler[] = [
                { kind: 'primary', begin: () => ({ prepare: (headers: Headers): void => headers.set('Authorization', 'Bearer primary') }) },
                { kind: 'supplementary', begin: () => ({
                    prepare(headers: Headers): void { if (acquired) headers.set('ShareWise-Attestation', 'domain.jwt.value'); },
                    recover: () => ({ key: 'membership', async run(primaryOnly): Promise<void> {
                        await primaryOnly.post('/groups/v1/domain-principal/attestations', {}, { mode: 'cors' });
                        acquired = true;
                    } }),
                }) },
            ];
            const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
                const target: URL = new URL(String(input), document.baseURI);
                expect(target.origin).toBe(origin);
                expect(String(input)).toBe(target.href);
                expect(init?.mode).toBe('same-origin');
                expect(init?.redirect).toBe('error');
                const headers: Headers = new Headers(init?.headers);
                expect(headers.get('Authorization')).toBe('Bearer primary');
                if (target.pathname.endsWith('/attestations')) {
                    expect(headers.has('ShareWise-Attestation')).toBe(false);
                    return new Response(null, { status: 204 });
                }
                return acquired ? new Response(null, { status: 204 }) : denial();
            });
            await expect(createApiClient(pipeline, fetchImpl, origin).post('/finance/v1/expenses', {}, { mode: 'cors', redirect: 'follow' })).resolves.toBeUndefined();
            expect(fetchImpl.mock.calls.map(call => call[0])).toEqual([
                `${origin}/api/finance/v1/expenses`, `${origin}/api/groups/v1/domain-principal/attestations`, `${origin}/api/finance/v1/expenses`,
            ]);
        } finally { base.remove(); }
    });
});

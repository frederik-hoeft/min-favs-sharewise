// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { createApiClient, parseErrorResponse } from '@/lib/api';
import { parseAuthorizationMetadata } from '@/lib/api/authorization-protocol';
import { credentialReference, issueCredential } from '@/core/authorization/credential';

const base = { requirementId: 'target', credential: { authority: 'groups', kind: 'membership', scope: 'X' }, cache: { policy: 'path_affine' } };
const now = Date.parse('2026-11-26T12:00:00Z');
const ref = credentialReference(base.credential);
const signal = new AbortController().signal;

describe('authorization protocol boundary', () => {
    it.each(['https://evil.test', '../iam', 'groups/foo', 'groups%2fother', 'groups?x=y', 'Groups', 'a'.repeat(65)])('rejects authority %s before route construction', authority => {
        expect(parseAuthorizationMetadata('attestation_required', { ...base, credential: { ...base.credential, authority } })).toBeUndefined();
    });
    it.each([null, {}, { policy: 'unknown' }, { policy: 'path_affine', parameters: ['target'] },
        { policy: 'query_affine', parameters: ['target', 'target'] }, { policy: 'query_affine', parameters: [] }])('malformed hint %j never broadens affinity', cache => {
        expect(parseAuthorizationMetadata('attestation_required', { ...base, cache })).toMatchObject({ cache: { policy: 'never' } });
    });
    it.each([-1, 4, 1.5, '0', null])('rejects uncorrelatable index %s', rejectedIndex => {
        expect(parseAuthorizationMetadata('invalid_attestation', { rejectedIndex })).toBeUndefined();
    });
    it('keeps authorization metadata only for actual 401 response and recognizes backend policy encoding', async () => {
        const payload = JSON.stringify({ status: 401, code: 'attestation_required', authorization: { ...base, cache: { policy: 'path_affine', parameters: [] } } });
        expect((await parseErrorResponse(new Response(payload, { status: 403, headers: { 'Content-Type': 'application/problem+json' } }))).authorization).toBeUndefined();
        expect((await parseErrorResponse(new Response(payload, { status: 401, headers: { 'Content-Type': 'application/problem+json' } }))).authorization).toMatchObject({ type: 'required', cache: { policy: 'path_affine' } });
    });
    it.each([
        { attestation: 'not-jws', expiresAt: '2026-11-26T12:02:00Z' },
        { attestation: 'x.y.z', expiresAt: '2026-11-26T12:00:00Z' },
        { attestation: 'x.y.z', expiresAt: '2026-11-26T13:00:00Z' },
        { attestation: 'x.y.z', expiresAt: '2026-11-26T12:02:00+01:00' },
        { attestation: 'x.y.' + 'z'.repeat(2048), expiresAt: '2026-11-26T12:02:00Z' },
        { attestation: 'x.y.z', expiresAt: null },
    ])('rejects invalid issuance response %#', async payload => {
        const client = createApiClient([], async () => new Response(JSON.stringify(payload), { status: 200, headers: { 'Content-Type': 'application/json' } }));
        await expect(issueCredential(client, ref, () => now, signal)).rejects.toMatchObject({ code: 'invalid_attestation_response' });
    });
    it('uses the opaque credential and bounded advisory expiry without parsing JWT claims', async () => {
        const client = createApiClient([], async (path, init) => {
            expect(path).toBe('http://sharewise.invalid/api/groups/v1/domain-principal/attestations');
            expect(init?.body).toBe('{"kind":"membership","scope":"X"}');
            return new Response(JSON.stringify({ attestation: 'x.y.z', expiresAt: '2026-11-26T12:02:00.123456789Z' }), { headers: { 'Content-Type': 'application/json' } });
        });
        await expect(issueCredential(client, ref, () => now, signal)).resolves.toEqual({ token: 'x.y.z', expiresAt: now + 120_123 });
    });
});

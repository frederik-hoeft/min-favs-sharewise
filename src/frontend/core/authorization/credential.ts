import { ApiError, type ApiClient } from '@/lib/api';
import { MAX_ATTESTATION_LENGTH, type CredentialDescriptor } from '@/lib/api/authorization-protocol.ts';

export interface CredentialReference {
    readonly key: string;
    readonly descriptor: CredentialDescriptor;
}

export function credentialReference(descriptor: CredentialDescriptor): CredentialReference {
    const immutable: CredentialDescriptor = Object.freeze({ ...descriptor });
    return Object.freeze({ key: JSON.stringify([immutable.authority, immutable.kind, immutable.scope]), descriptor: immutable });
}

export interface IssuedCredential {
    readonly token: string;
    readonly expiresAt: number;
}

const COMPACT_JWS: RegExp = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const ISO_INSTANT: RegExp = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
// A generic client safety bound, not a cryptographic lifetime decision (Group uses 120s).
const MAX_ADVISORY_LIFETIME_MS: number = 10 * 60_000;

export function unavailableAttestation(): ApiError {
    return new ApiError({ status: 0, code: 'attestation_unavailable', title: 'Attestation Unavailable',
        detail: 'The required supplementary credential could not be prepared.' });
}

function invalidResponse(): ApiError {
    return new ApiError({ status: 0, code: 'invalid_attestation_response', title: 'Invalid Attestation Response',
        detail: 'The authority returned an unusable supplementary credential.' });
}

/** The only acquisition route is the configured same-origin API namespace, never a response URL. */
export async function issueCredential(api: ApiClient, reference: CredentialReference, now: () => number,
    signal: AbortSignal): Promise<IssuedCredential> {
    const { authority, kind, scope }: CredentialDescriptor = reference.descriptor;
    const payload: unknown = await api.post<unknown>(`/${authority}/v1/domain-principal/attestations`, { kind, scope },
        { signal, expectedStatus: 200 });
    if (!payload || typeof payload !== 'object' || !('attestation' in payload) || !('expiresAt' in payload) ||
        typeof payload.attestation !== 'string' || payload.attestation.length > MAX_ATTESTATION_LENGTH ||
        !COMPACT_JWS.test(payload.attestation) || typeof payload.expiresAt !== 'string' ||
        !ISO_INSTANT.test(payload.expiresAt)) throw invalidResponse();
    const expiresAt: number = Date.parse(payload.expiresAt);
    const observed: number = now();
    // Date.parse can normalize invalid calendar dates; compare the canonical UTC seconds.
    if (!Number.isFinite(expiresAt) || new Date(expiresAt).toISOString().slice(0, 19) !== payload.expiresAt.slice(0, 19) ||
        expiresAt <= observed || expiresAt - observed > MAX_ADVISORY_LIFETIME_MS) throw invalidResponse();
    return { token: payload.attestation, expiresAt };
}

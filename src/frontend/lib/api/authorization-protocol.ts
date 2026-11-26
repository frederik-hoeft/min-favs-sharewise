/** Wire bounds shared by the generic attestation protocol and its HTTP transport. */
export const ATTESTATION_HEADER: string = 'ShareWise-Attestation';
export const MAX_ATTESTATIONS: number = 4;
export const MAX_ATTESTATION_LENGTH: number = 2048;
export const MAX_ATTESTATION_HEADER_LENGTH: number = 8192;

export interface CredentialDescriptor {
    readonly authority: string;
    readonly kind: string;
    readonly scope: string;
}

export type AffinityPolicy =
    | { readonly policy: 'never' | 'path_affine' | 'request_affine' }
    | { readonly policy: 'query_affine'; readonly parameters: readonly string[] };

export interface AttestationChallenge {
    readonly type: 'required';
    readonly requirementId: string;
    readonly credential: CredentialDescriptor;
    readonly cache: AffinityPolicy;
}

export interface AttestationRejection {
    readonly type: 'invalid';
    readonly rejectedIndex: number;
}

export type AuthorizationMetadata = AttestationChallenge | AttestationRejection;

const IDENTIFIER: RegExp = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const CONTROL: RegExp = /[\u0000-\u001f\u007f-\u009f]/u;
const NEVER: AffinityPolicy = Object.freeze({ policy: 'never' });

function object(value: unknown): Record<string, unknown> | undefined {
    return typeof value === 'object' && value !== null && !Array.isArray(value)
        ? value as Record<string, unknown> : undefined;
}

function boundedText(value: unknown, limit: number): value is string {
    return typeof value === 'string' && value.length <= limit && value.trim().length > 0 && !CONTROL.test(value);
}

function identifier(value: unknown): value is string {
    return boundedText(value, 64) && IDENTIFIER.test(value);
}

function affinity(value: unknown): AffinityPolicy {
    const cache: Record<string, unknown> | undefined = object(value);
    if (!cache) return NEVER;
    if (cache.policy === 'path_affine' || cache.policy === 'request_affine') {
        return cache.parameters === undefined || (Array.isArray(cache.parameters) && cache.parameters.length === 0)
            ? Object.freeze({ policy: cache.policy }) : NEVER;
    }
    if (cache.policy === 'query_affine' && Array.isArray(cache.parameters) &&
        cache.parameters.length > 0 && cache.parameters.length <= 8 &&
        cache.parameters.every((parameter: unknown): parameter is string => boundedText(parameter, 64)) &&
        new Set(cache.parameters).size === cache.parameters.length) {
        return Object.freeze({ policy: 'query_affine', parameters: Object.freeze([...cache.parameters].sort()) });
    }
    return NEVER;
}

/** Parse only recognized, bounded metadata; invalid hints never broaden a cache match. */
export function parseAuthorizationMetadata(code: string, value: unknown): AuthorizationMetadata | undefined {
    const data: Record<string, unknown> | undefined = object(value);
    if (!data) return;
    if (code === 'invalid_attestation') {
        const index: unknown = data.rejectedIndex;
        if (typeof index === 'number' && Number.isInteger(index) && index >= 0 && index < MAX_ATTESTATIONS) {
            return Object.freeze({ type: 'invalid', rejectedIndex: index });
        }
        return;
    }
    if (code !== 'attestation_required' || !boundedText(data.requirementId, 128)) return;
    const credential: Record<string, unknown> | undefined = object(data.credential);
    if (!credential || !identifier(credential.authority) || !identifier(credential.kind) ||
        !boundedText(credential.scope, 256)) return;
    return Object.freeze({ type: 'required', requirementId: data.requirementId,
        credential: Object.freeze({ authority: credential.authority, kind: credential.kind, scope: credential.scope }),
        cache: affinity(data.cache) });
}

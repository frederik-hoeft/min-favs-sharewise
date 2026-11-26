import type { ApiRequestContext } from '@/lib/api';
import type { AffinityPolicy, AttestationChallenge } from '@/lib/api/authorization-protocol.ts';
import { type CredentialReference } from './credential.ts';

const MAX_TARGET_LENGTH: number = 4096;
const MAX_REQUIREMENTS_PER_BUCKET: number = 8;
const MAX_PROJECTIONS_PER_REQUIREMENT: number = 8;

interface RequirementAffinity {
    readonly policy: AffinityPolicy;
    readonly sequence: number;
    readonly entries: Map<string, CredentialReference>;
}

type Bucket = Map<string, RequirementAffinity>;

export interface RequirementRequest {
    readonly request: ApiRequestContext;
    readonly bucketKey?: string;
    readonly sequence: number;
    readonly predictions: ReadonlyMap<string, CredentialReference>;
}

export interface PendingAffinity {
    readonly bucketKey: string;
    readonly requirementId: string;
    readonly record: RequirementAffinity;
    readonly projection: string;
    readonly reference: CredentialReference;
}

function bucketKey(request: ApiRequestContext): string | undefined {
    if (request.target.length > MAX_TARGET_LENGTH || request.origin.length > 512 || request.method.length > 32) return;
    return JSON.stringify([request.origin, request.method, request.pathname]);
}

/** Preserve actual request-target semantics, without interpreting a domain scope or body. */
export function project(request: ApiRequestContext, policy: AffinityPolicy): string | undefined {
    if (policy.policy === 'never') return;
    if (policy.policy === 'path_affine') return '';
    const query: string = request.target.slice(request.pathname.length);
    if (policy.policy === 'request_affine') return query;
    if (policy.policy !== 'query_affine') return;
    const selected: Set<string> = new Set(policy.parameters);
    const pairs: [string, string, boolean, string][] = [];
    if (query.length <= 1) return JSON.stringify(pairs);
    // Reject ambiguous form parsing (malformed escapes/UTF-8, separators or empty fields).
    // Retain original encodings and ordering as conservative, collision-free distinctions.
    if (!query.startsWith('?') || query.includes(';')) return;
    try {
        for (const pair of query.slice(1).split('&')) {
            if (!pair) return;
            const eq: number = pair.indexOf('=');
            const rawName: string = eq < 0 ? pair : pair.slice(0, eq);
            const rawValue: string = eq < 0 ? '' : pair.slice(eq + 1);
            const name: string = decodeURIComponent(rawName.replace(/\+/g, ' '));
            decodeURIComponent(rawValue.replace(/\+/g, ' '));
            if (!name) return;
            if (selected.has(name)) pairs.push([name, rawName, eq >= 0, rawValue]);
        }
        return JSON.stringify(pairs);
    } catch { return; }
}

function samePolicy(left: AffinityPolicy, right: AffinityPolicy): boolean {
    return left.policy === right.policy && (left.policy !== 'query_affine' ||
        (right.policy === 'query_affine' && left.parameters.length === right.parameters.length &&
            left.parameters.every((name: string, i: number): boolean => name === right.parameters[i])));
}

/** Independent bounded associations. Each requirement ID owns its own projection. */
export class RequirementCache {
    private readonly buckets: Map<string, Bucket> = new Map<string, Bucket>();
    private sequence: number = 0;
    // Eviction forgets version history. Prevent already-started requests from repopulating forgotten slots.
    private evictedThrough: number = 0;

    constructor(private readonly bucketCapacity: number = 32) { }

    begin(request: ApiRequestContext): RequirementRequest {
        const key: string | undefined = this.bucketCapacity > 0 ? bucketKey(request) : undefined;
        const predictions: Map<string, CredentialReference> = new Map<string, CredentialReference>();
        if (key !== undefined) {
            const bucket: Bucket | undefined = this.buckets.get(key);
            for (const [id, record] of bucket ?? []) {
                const projection: string | undefined = project(request, record.policy);
                const reference: CredentialReference | undefined = projection === undefined ? undefined : record.entries.get(projection);
                if (reference) predictions.set(id, reference);
            }
        }
        return { request, bucketKey: key, sequence: ++this.sequence, predictions };
    }

    /** Invalidate an obsolete prediction immediately; publish the new association only on successful replay. */
    observe(context: RequirementRequest, challenge: AttestationChallenge, reference: CredentialReference): PendingAffinity | undefined {
        const key: string | undefined = context.bucketKey;
        if (key === undefined || context.sequence <= this.evictedThrough) return;
        let bucket: Bucket | undefined = this.buckets.get(key);
        if (!bucket) {
            bucket = new Map();
            this.buckets.set(key, bucket);
            if (this.buckets.size > this.bucketCapacity) {
                this.buckets.delete(this.buckets.keys().next().value!);
                this.evictedThrough = this.sequence;
            }
        } else {
            this.buckets.delete(key);
            this.buckets.set(key, bucket);
        }
        const old: RequirementAffinity | undefined = bucket.get(challenge.requirementId);
        // A delayed response from an older logical operation must not undo newer learning.
        if (old && old.sequence > context.sequence) return;
        const projection: string | undefined = project(context.request, challenge.cache);
        const entries: Map<string, CredentialReference> = old && samePolicy(old.policy, challenge.cache) ? new Map(old.entries) : new Map<string, CredentialReference>();
        if (projection !== undefined && entries.get(projection)?.key !== reference.key) entries.delete(projection);
        // Even a never policy leaves a bounded version marker to prevent stale publication.
        const record: RequirementAffinity = { policy: challenge.cache, sequence: context.sequence, entries };
        bucket.delete(challenge.requirementId);
        bucket.set(challenge.requirementId, record);
        if (bucket.size > MAX_REQUIREMENTS_PER_BUCKET) {
            bucket.delete(bucket.keys().next().value!);
            this.evictedThrough = this.sequence;
        }
        return projection === undefined ? undefined : {
            bucketKey: key, requirementId: challenge.requirementId, record, projection, reference,
        };
    }

    commit(pending: PendingAffinity): void {
        if (this.buckets.get(pending.bucketKey)?.get(pending.requirementId) !== pending.record) return;
        const entries: Map<string, CredentialReference> = pending.record.entries;
        entries.delete(pending.projection);
        entries.set(pending.projection, pending.reference);
        if (entries.size > MAX_PROJECTIONS_PER_REQUIREMENT) entries.delete(entries.keys().next().value!);
    }

    clear(): void { this.buckets.clear(); }
}

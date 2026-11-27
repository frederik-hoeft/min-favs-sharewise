import { ApiError, requestCanceled, sessionContextChanged, type ApiRequestHandler, type ApiRequestContext, type ApiClient,
    type RequestHandler, type SentRequest, type RecoveryAction } from '@/lib/api';
import { ATTESTATION_HEADER, MAX_ATTESTATIONS, MAX_ATTESTATION_HEADER_LENGTH, type AttestationChallenge, type AuthorizationMetadata } from '@/lib/api/authorization-protocol.ts';
import { credentialReference, issueCredential, unavailableAttestation, type CredentialReference, type IssuedCredential } from './credential.ts';
import type { AuthorizationSnapshot } from './diagnostics.ts';
import { CredentialCache, type CredentialGeneration } from './credential-cache.ts';
import { RequirementCache, type PendingAffinity, type RequirementRequest } from './requirement-cache.ts';

export interface AttestationCacheOptions {
    readonly now?: () => number;
    /** Zero disables the credential cache; logical-request evidence remains available. */
    readonly credentialCapacity?: number;
    /** Zero disables requirement prediction and learning. */
    readonly requirementBucketCapacity?: number;
}

interface LifetimeSource {
    current(): number | undefined;
    subscribe(listener: () => void): () => void;
}

interface SessionCaches {
    readonly lifetime: number;
    readonly credentials: CredentialCache;
    readonly requirements: RequirementCache;
}

interface RequirementSelection {
    readonly reference: CredentialReference;
    /** Required by a challenge in this logical request, rather than a prediction. */
    readonly required?: CredentialGeneration;
}

interface Presented {
    readonly token: string;
    readonly generations: CredentialGeneration[];
}

const ISSUANCE_ROUTE: RegExp = /^\/api\/[a-z][a-z0-9]*(?:-[a-z0-9]+)*\/v1\/domain-principal\/attestations$/;

/** One generic handler for all compliant authorities, with independent lifetime-scoped caches. */
export class AttestationHandler implements ApiRequestHandler {
    readonly kind: ApiRequestHandler['kind'] = 'supplementary';
    private caches?: SessionCaches;
    private readonly unsubscribe: () => void;
    private readonly now: () => number;
    private closed: boolean = false;

    constructor(private readonly session: LifetimeSource, private readonly options: AttestationCacheOptions = {}) {
        this.now = options.now ?? Date.now;
        for (const capacity of [options.credentialCapacity, options.requirementBucketCapacity]) {
            if (capacity !== undefined && (!Number.isSafeInteger(capacity) || capacity < 0 || capacity > 256)) {
                throw new RangeError('Invalid authorization cache capacity');
            }
        }
        this.unsubscribe = session.subscribe(() => {
            if (this.caches && this.caches.lifetime !== session.current()) this.clear();
        });
    }

    private clear(): void {
        const old: SessionCaches | undefined = this.caches;
        this.caches = undefined;
        old?.credentials.close();
        old?.requirements.clear();
    }

    dispose(): void { this.closed = true; this.unsubscribe(); this.clear(); }

    /** Observation must neither initialize caches nor touch their eviction order. */
    snapshot(): AuthorizationSnapshot {
        const lifetime: number | undefined = this.session.current();
        const caches: SessionCaches | undefined = this.caches?.lifetime === lifetime ? this.caches : undefined;
        const capturedAt: number = this.now();
        return Object.freeze({ lifetime: lifetime ?? null, capturedAt,
            credentials: caches?.credentials.diagnostics(capturedAt) ?? Object.freeze([]),
            requirements: caches?.requirements.diagnostics() ?? Object.freeze([]),
            inFlight: caches?.credentials.acquisitionDiagnostics() ?? Object.freeze([]) });
    }

    private current(): SessionCaches {
        const lifetime: number | undefined = this.session.current();
        if (this.closed || lifetime === undefined) throw sessionContextChanged();
        if (this.caches?.lifetime !== lifetime) {
            this.clear();
            this.caches = { lifetime, credentials: new CredentialCache(this.now, this.options.credentialCapacity),
                requirements: new RequirementCache(this.options.requirementBucketCapacity) };
        }
        return this.caches;
    }

    validateHeaders(headers: Readonly<Headers>): void {
        if (headers.has(ATTESTATION_HEADER)) {
            throw new ApiError({ status: 0, code: 'invalid_request_context', title: 'Invalid Request Context',
                detail: 'Managed requests cannot supply their own supplementary credentials.' });
        }
    }

    begin(request: ApiRequestContext): RequestHandler | undefined {
        // Standard issuance is primary-only even when called directly; no authority-specific switch.
        if (ISSUANCE_ROUTE.test(request.pathname)) return;
        let bound: SessionCaches | undefined;
        let affinity: RequirementRequest | undefined;
        const selections: Map<string, RequirementSelection> = new Map<string, RequirementSelection>();
        const pending: Map<string, PendingAffinity> = new Map<string, PendingAffinity>();
        const rejectedGenerations: Map<string, number> = new Map<string, number>();
        let presented: Presented[] = [];
        const assertCurrent: () => void = () => {
            if (bound && (this.caches !== bound || this.session.current() !== bound.lifetime)) throw sessionContextChanged();
        };
        const assertInterested: () => void = () => {
            assertCurrent();
            if (request.signal?.aborted) throw requestCanceled();
        };
        function sentMatches(sent: SentRequest): boolean {
            return sent.headers.get(ATTESTATION_HEADER) ===
                (presented.length ? presented.map((item: Presented): string => item.token).join(', ') : null);
        }

        function rejected(sent: SentRequest, index: number): Presented | undefined {
            return sentMatches(sent) ? presented[index] : undefined;
        }

        function evict(item: Presented): void {
            if (!bound) return;
            const keys: Set<string> = new Set<string>();
            for (const generation of item.generations) {
                bound.credentials.reject(generation);
                keys.add(generation.reference.key);
                rejectedGenerations.set(generation.reference.key, generation.generation);
            }
            for (const [id, selection] of selections) {
                if (keys.has(selection.reference.key)) {
                    selections.delete(id);
                    pending.delete(id);
                }
            }
        }

        function requireCredential(challenge: AttestationChallenge, failure: ApiError): RecoveryAction | undefined {
            if (!bound || !affinity) return;
            const caches: SessionCaches = bound;
            const context: RequirementRequest = affinity;
            const reference: CredentialReference = credentialReference(challenge.credential);
            // Another ID cannot force replay with an unchanged already-presented credential set.
            if (presented.some((item: Presented): boolean => item.generations.some((entry: CredentialGeneration): boolean => entry.reference.key === reference.key))) return;
            return {
                key: JSON.stringify(['required', challenge.requirementId, reference.key, rejectedGenerations.get(reference.key) ?? null]),
                async run(primaryOnly: ApiClient): Promise<void> {
                    assertInterested();
                    const learned: PendingAffinity | undefined = caches.requirements.observe(context, challenge, reference);
                    pending.delete(challenge.requirementId);
                    let generation: CredentialGeneration;
                    try {
                        generation = await caches.credentials.acquire(reference,
                            (signal: AbortSignal): Promise<IssuedCredential> => issueCredential(primaryOnly, reference, now, signal), request.signal);
                    } catch (cause: unknown) {
                        // Denial explains the original requirement. Availability and IAM failures retain their meaning.
                        if (cause instanceof ApiError && (cause.status === 403 || cause.status === 404)) {
                            throw new ApiError({ status: cause.status, code: cause.code, title: cause.title,
                                detail: failure.detail, fieldErrors: cause.fieldErrors, cause });
                        }
                        throw cause;
                    }
                    assertInterested();
                    if (presented.some((item: Presented): boolean => item.token === generation.token)) throw failure;
                    selections.set(challenge.requirementId, { reference, required: generation });
                    if (learned) pending.set(challenge.requirementId, learned);
                },
            };
        }
        const now: () => number = this.now;

        return {
            assertCurrent,
            prepare: (headers: Headers): void => {
                assertInterested();
                if (!bound) {
                    bound = this.current();
                    affinity = bound.requirements.begin(request);
                    for (const [id, reference] of affinity.predictions) selections.set(id, { reference });
                }
                const cache: CredentialCache = bound.credentials;
                const next: Presented[] = [];
                let size: number = 0;
                // Required evidence first; optional predictions may be omitted under header pressure.
                const ordered: RequirementSelection[] = [...selections.values()].sort((a: RequirementSelection, b: RequirementSelection): number =>
                    Number(b.required !== undefined) - Number(a.required !== undefined));
                for (const selection of ordered) {
                    const entry: CredentialGeneration | undefined = cache.get(selection.reference) ?? selection.required;
                    if (!entry || !cache.usable(entry)) {
                        if (selection.required) throw unavailableAttestation();
                        continue;
                    }
                    const same: Presented | undefined = next.find((item: Presented): boolean => item.token === entry.token);
                    if (same) {
                        if (!same.generations.includes(entry)) same.generations.push(entry);
                        continue;
                    }
                    const addition: number = entry.token.length + (next.length ? 2 : 0);
                    if (next.length >= MAX_ATTESTATIONS || size + addition > MAX_ATTESTATION_HEADER_LENGTH) {
                        if (selection.required) throw unavailableAttestation();
                        continue;
                    }
                    next.push({ token: entry.token, generations: [entry] });
                    size += addition;
                }
                presented = next;
                if (next.length) headers.set(ATTESTATION_HEADER, next.map((item: Presented): string => item.token).join(', '));
            },
            recover(failure: ApiError, sent: SentRequest): RecoveryAction | undefined {
                const metadata: AuthorizationMetadata | undefined = failure.authorization;
                if (!metadata || !sentMatches(sent)) return;
                if (metadata.type === 'required') return requireCredential(metadata, failure);
                const item: Presented | undefined = rejected(sent, metadata.rejectedIndex);
                if (!item) return;
                return { key: JSON.stringify(['invalid', item.generations.map((entry: CredentialGeneration): number => entry.generation)]),
                    async run(): Promise<void> { evict(item); } };
            },
            onTerminalFailure(failure: ApiError, sent: SentRequest): void {
                const metadata: AuthorizationMetadata | undefined = failure.authorization;
                if (metadata?.type === 'invalid') {
                    const item: Presented | undefined = rejected(sent, metadata.rejectedIndex);
                    if (item) evict(item);
                } else if (metadata?.type === 'required' && bound && affinity && sentMatches(sent)) {
                    // A terminal challenge can still invalidate an obsolete prediction.
                    // Do not publish a new affinity without a successful protected request.
                    bound.requirements.observe(affinity, metadata, credentialReference(metadata.credential));
                }
            },
            onSuccess(sent: SentRequest): void {
                if (!bound || !sentMatches(sent)) return;
                for (const [id, learned] of pending) {
                    if (selections.get(id)?.reference.key === learned.reference.key && presented.some((item: Presented): boolean =>
                        item.generations.some((entry: CredentialGeneration): boolean => entry.reference.key === learned.reference.key))) {
                        bound.requirements.commit(learned);
                    }
                }
            },
        };
    }
}

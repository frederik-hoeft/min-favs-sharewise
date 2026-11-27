import { requestCanceled, sessionContextChanged } from '@/lib/api';
import type { AcquisitionDiagnostic, CredentialDiagnostic } from './diagnostics.ts';
import { unavailableAttestation, type CredentialReference, type IssuedCredential } from './credential.ts';

export interface CredentialGeneration extends IssuedCredential {
    readonly reference: CredentialReference;
    readonly generation: number;
    rejected: boolean;
}

interface Flight {
    readonly controller: AbortController;
    readonly promise: Promise<CredentialGeneration>;
    waiters: number;
    settled: boolean;
}

/** One current generation per descriptor, scoped to one authenticated lifetime. */
export class CredentialCache {
    private readonly entries: Map<string, CredentialGeneration> = new Map<string, CredentialGeneration>();
    private readonly flights: Map<string, Flight> = new Map<string, Flight>();
    private generation: number = 0;
    private closed: boolean = false;

    constructor(private readonly now: () => number, private readonly capacity: number = 128,
        private readonly flightCapacity: number = 32) { }

    usable(value: CredentialGeneration, observedAt: number = this.now()): boolean {
        return !this.closed && !value.rejected && value.expiresAt > observedAt;
    }

    get(reference: CredentialReference): CredentialGeneration | undefined {
        const entry: CredentialGeneration | undefined = this.entries.get(reference.key);
        if (!entry) return;
        this.entries.delete(reference.key);
        if (!this.usable(entry)) return;
        this.entries.set(reference.key, entry); // deterministic LRU
        return entry;
    }

    reject(entry: CredentialGeneration): void {
        entry.rejected = true;
        if (this.entries.get(entry.reference.key) === entry) this.entries.delete(entry.reference.key);
    }

    async acquire(reference: CredentialReference, load: (signal: AbortSignal) => Promise<IssuedCredential>,
        signal?: AbortSignal): Promise<CredentialGeneration> {
        if (this.closed) throw sessionContextChanged();
        if (signal?.aborted) throw requestCanceled();
        const cached: CredentialGeneration | undefined = this.get(reference);
        if (cached) return cached;
        let flight: Flight | undefined = this.flights.get(reference.key);
        if (!flight) {
            if (this.flights.size >= this.flightCapacity) throw unavailableAttestation();
            const controller: AbortController = new AbortController();
            const created: Flight = {
                controller, waiters: 0, settled: false,
                promise: Promise.resolve().then(async (): Promise<CredentialGeneration> => {
                    if (controller.signal.aborted) throw this.closed ? sessionContextChanged() : requestCanceled();
                    const issued: IssuedCredential = await load(controller.signal);
                    if (this.closed) throw sessionContextChanged();
                    if (controller.signal.aborted || this.flights.get(reference.key) !== created) throw requestCanceled();
                    const entry: CredentialGeneration = { ...issued, reference, generation: ++this.generation, rejected: false };
                    if (!this.usable(entry)) throw unavailableAttestation();
                    if (this.capacity > 0) {
                        this.entries.set(reference.key, entry);
                        while (this.entries.size > this.capacity) this.entries.delete(this.entries.keys().next().value!);
                    }
                    return entry;
                }).finally((): void => {
                    created.settled = true;
                    if (this.flights.get(reference.key) === created) this.flights.delete(reference.key);
                }),
            };
            this.flights.set(reference.key, created);
            flight = created;
        }
        return this.wait(reference.key, flight, signal);
    }

    private wait(key: string, flight: Flight, signal?: AbortSignal): Promise<CredentialGeneration> {
        flight.waiters++;
        return new Promise<CredentialGeneration>((resolve: (value: CredentialGeneration | PromiseLike<CredentialGeneration>) => void, reject: (reason?: unknown) => void): void => {
            let done: boolean = false;
            const finish: () => boolean = () => {
                if (done) return false;
                done = true;
                signal?.removeEventListener('abort', canceled);
                flight.controller.signal.removeEventListener('abort', abandoned);
                if (--flight.waiters === 0 && !flight.settled) {
                    if (this.flights.get(key) === flight) this.flights.delete(key);
                    flight.controller.abort();
                }
                return true;
            };
            const canceled: () => void = () => { if (finish()) reject(requestCanceled()); };
            const abandoned: () => void = () => { if (finish()) reject(this.closed ? sessionContextChanged() : requestCanceled()); };
            signal?.addEventListener('abort', canceled, { once: true });
            flight.controller.signal.addEventListener('abort', abandoned, { once: true });
            // Register promise handlers even if the last waiter has already canceled.
            void flight.promise.then(
                (value: CredentialGeneration): void => { if (finish()) resolve(value); },
                (error: unknown): void => { if (finish()) reject(error); },
            );
            if (signal?.aborted) canceled();
            else if (flight.controller.signal.aborted) abandoned();
        });
    }

    diagnostics(observedAt: number): readonly CredentialDiagnostic[] {
        return Object.freeze(Array.from(this.entries.values(), (entry: CredentialGeneration): CredentialDiagnostic =>
            Object.freeze({ authority: entry.reference.descriptor.authority, kind: entry.reference.descriptor.kind,
                scope: entry.reference.descriptor.scope, key: entry.reference.key, generation: entry.generation,
                expiresAt: entry.expiresAt, remainingMs: Math.max(0, entry.expiresAt - observedAt), usable: this.usable(entry, observedAt) })));
    }

    acquisitionDiagnostics(): readonly AcquisitionDiagnostic[] {
        return Object.freeze(Array.from(this.flights, ([key, flight]: [string, Flight]): AcquisitionDiagnostic =>
            Object.freeze({ key, waiters: flight.waiters })));
    }

    close(): void {
        this.closed = true;
        for (const flight of this.flights.values()) flight.controller.abort();
        this.flights.clear();
        this.entries.clear();
    }
}

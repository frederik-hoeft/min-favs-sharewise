import { requestCanceled, sessionContextChanged } from '@/lib/api';
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
    private readonly entries = new Map<string, CredentialGeneration>();
    private readonly flights = new Map<string, Flight>();
    private generation = 0;
    private closed = false;

    constructor(private readonly now: () => number, private readonly capacity = 128,
        private readonly flightCapacity = 32) { }

    usable(value: CredentialGeneration): boolean {
        return !this.closed && !value.rejected && value.expiresAt > this.now();
    }

    get(reference: CredentialReference): CredentialGeneration | undefined {
        const entry = this.entries.get(reference.key);
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
        const cached = this.get(reference);
        if (cached) return cached;
        let flight = this.flights.get(reference.key);
        if (!flight) {
            if (this.flights.size >= this.flightCapacity) throw unavailableAttestation();
            const controller = new AbortController();
            const created: Flight = {
                controller, waiters: 0, settled: false,
                promise: Promise.resolve().then(async () => {
                    if (controller.signal.aborted) throw this.closed ? sessionContextChanged() : requestCanceled();
                    const issued = await load(controller.signal);
                    if (this.closed) throw sessionContextChanged();
                    if (controller.signal.aborted || this.flights.get(reference.key) !== created) throw requestCanceled();
                    const entry = { ...issued, reference, generation: ++this.generation, rejected: false };
                    if (!this.usable(entry)) throw unavailableAttestation();
                    if (this.capacity > 0) {
                        this.entries.set(reference.key, entry);
                        while (this.entries.size > this.capacity) this.entries.delete(this.entries.keys().next().value!);
                    }
                    return entry;
                }).finally(() => {
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
        return new Promise((resolve, reject) => {
            let done = false;
            const finish = () => {
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
            const canceled = () => { if (finish()) reject(requestCanceled()); };
            const abandoned = () => { if (finish()) reject(this.closed ? sessionContextChanged() : requestCanceled()); };
            signal?.addEventListener('abort', canceled, { once: true });
            flight.controller.signal.addEventListener('abort', abandoned, { once: true });
            // Register promise handlers even if the last waiter has already canceled.
            void flight.promise.then(value => { if (finish()) resolve(value); }, error => { if (finish()) reject(error); });
            if (signal?.aborted) canceled();
            else if (flight.controller.signal.aborted) abandoned();
        });
    }

    close(): void {
        this.closed = true;
        for (const flight of this.flights.values()) flight.controller.abort();
        this.flights.clear();
        this.entries.clear();
    }
}

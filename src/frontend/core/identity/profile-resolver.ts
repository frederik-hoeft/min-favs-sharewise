import { requestCanceled, sessionContextChanged } from '@/lib/api';
import type { PublicProfile } from './models.ts';

export type ProfileResolution =
    | { readonly status: 'resolved'; readonly displayName: string }
    | { readonly status: 'missing' }
    | { readonly status: 'unavailable'; readonly displayName?: string };

interface CacheEntry {
    readonly value: ProfileResolution;
    readonly expiresAt: number;
}
interface PendingProfile {
    readonly promise: Promise<ProfileResolution>;
    readonly complete: (result: ProfileResolution) => void;
    readonly staleName?: string;
}

const MAX_BATCH: number = 100;
const CAPACITY: number = 512;
const POSITIVE_TTL: number = 60_000;
const NEGATIVE_TTL: number = 10_000;

type FetchProfiles = (ids: readonly string[], signal: AbortSignal) => Promise<readonly PublicProfile[]>;

/** One ordinary presentation-data cache per authenticated lifetime, independent of credential caches. */
export class ProfileResolver {
    private readonly cache: Map<string, CacheEntry> = new Map<string, CacheEntry>();
    private readonly pending: Map<string, PendingProfile> = new Map<string, PendingProfile>();
    private readonly queued: Map<string, PendingProfile> = new Map<string, PendingProfile>();
    private readonly controller: AbortController = new AbortController();
    private running: boolean = false;

    constructor(private readonly fetchProfiles: FetchProfiles, private readonly assertCurrent: () => void,
        private readonly now: () => number = Date.now) { }

    async resolve(userIds: readonly string[], signal?: AbortSignal): Promise<ReadonlyMap<string, ProfileResolution>> {
        this.assertOpen();
        if (signal?.aborted) throw requestCanceled();
        const ids: string[] = [...new Set(userIds)];
        const results: Promise<ProfileResolution[]> = Promise.all(ids.map((id: string): Promise<ProfileResolution> => this.resolveOne(id)));
        this.schedule();
        // A consumer may stop waiting without aborting another consumer's shared lookup.
        let cancel: (() => void) | undefined;
        const canceled: Promise<never> = new Promise<never>((_resolve, reject): void => {
            cancel = (): void => reject(requestCanceled());
            signal?.addEventListener('abort', cancel, { once: true });
            if (signal?.aborted) cancel();
        });
        try {
            const values: ProfileResolution[] = await Promise.race([results, canceled]);
            this.assertOpen();
            return new Map(ids.map((id: string, index: number): [string, ProfileResolution] => [id, values[index]]));
        } finally {
            if (cancel) signal?.removeEventListener('abort', cancel);
        }
    }

    /** A confirmed rename wins over any earlier in-flight lookup of the same ID. */
    remember(profile: PublicProfile): void {
        this.assertOpen();
        const value: ProfileResolution = { status: 'resolved', displayName: profile.displayName };
        this.store(profile.userId, value);
        const pending: PendingProfile | undefined = this.pending.get(profile.userId);
        if (pending) {
            this.pending.delete(profile.userId);
            this.queued.delete(profile.userId);
            pending.complete(value);
        }
    }

    close(): void {
        this.controller.abort();
        for (const entry of this.pending.values()) entry.complete({ status: 'unavailable' });
        this.pending.clear(); this.queued.clear(); this.cache.clear();
    }

    private assertOpen(): void {
        if (this.controller.signal.aborted) throw sessionContextChanged();
        this.assertCurrent();
    }

    private resolveOne(id: string): Promise<ProfileResolution> {
        const old: CacheEntry | undefined = this.cache.get(id);
        if (old && old.expiresAt > this.now()) {
            this.cache.delete(id); this.cache.set(id, old);
            return Promise.resolve(old.value);
        }
        const existing: PendingProfile | undefined = this.pending.get(id);
        if (existing) return existing.promise;
        let complete!: (result: ProfileResolution) => void;
        const promise: Promise<ProfileResolution> = new Promise<ProfileResolution>((resolve): void => { complete = resolve; });
        const entry: PendingProfile = { promise, complete, staleName: old?.value.status === 'resolved' ? old.value.displayName : undefined };
        this.pending.set(id, entry); this.queued.set(id, entry);
        return promise;
    }

    private store(id: string, value: ProfileResolution): void {
        this.cache.delete(id);
        this.cache.set(id, { value, expiresAt: this.now() + (value.status === 'resolved' ? POSITIVE_TTL : NEGATIVE_TTL) });
        while (this.cache.size > CAPACITY) {
            const oldest: string | undefined = this.cache.keys().next().value;
            if (oldest === undefined) break;
            this.cache.delete(oldest);
        }
    }

    private schedule(): void {
        if (this.running || this.queued.size === 0) return;
        this.running = true;
        void Promise.resolve().then((): Promise<void> => this.drain());
    }

    private async drain(): Promise<void> {
        try {
            while (!this.controller.signal.aborted && this.queued.size > 0) {
                const batch: [string, PendingProfile][] = [];
                for (const entry of this.queued) {
                    batch.push(entry);
                    this.queued.delete(entry[0]);
                    if (batch.length === MAX_BATCH) break;
                }
                let names: Map<string, string> | undefined;
                try {
                    this.assertOpen();
                    const profiles: readonly PublicProfile[] = await this.fetchProfiles(batch.map(([id]): string => id), this.controller.signal);
                    this.assertOpen();
                    names = new Map(profiles.map((profile: PublicProfile): [string, string] => [profile.userId, profile.displayName]));
                } catch {
                    // A transport failure is never cached as a missing profile.
                }
                for (const [id, entry] of batch) {
                    if (this.pending.get(id) !== entry) continue;
                    this.pending.delete(id);
                    const name: string | undefined = names?.get(id);
                    const value: ProfileResolution = names === undefined ? { status: 'unavailable', displayName: entry.staleName }
                        : name === undefined ? { status: 'missing' } : { status: 'resolved', displayName: name };
                    if (names !== undefined) this.store(id, value);
                    entry.complete(value);
                }
            }
        } finally {
            this.running = false;
            this.schedule();
        }
    }
}

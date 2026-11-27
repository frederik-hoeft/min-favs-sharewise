import { requestCanceled } from '@/lib/api';
import type { PublicProfile } from './models.ts';

export type ProfileResolution =
    | { readonly status: 'resolved'; readonly displayName: string }
    | { readonly status: 'missing' }
    | { readonly status: 'unavailable'; readonly displayName?: string };

const MAX_BATCH: number = 100;
type FetchProfiles = (ids: readonly string[], signal?: AbortSignal) => Promise<readonly PublicProfile[]>;

/** Stateless resolution; consumers own presentation snapshots and cancellation. */
export class ProfileResolver {
    constructor(private readonly fetchProfiles: FetchProfiles, private readonly assertCurrent: () => void) { }

    async resolve(userIds: readonly string[], signal?: AbortSignal): Promise<ReadonlyMap<string, ProfileResolution>> {
        const assertActive: () => void = (): void => {
            this.assertCurrent();
            if (signal?.aborted) throw requestCanceled();
        };
        assertActive();
        const ids: string[] = [...new Set(userIds)];
        const result: Map<string, ProfileResolution> = new Map<string, ProfileResolution>();
        for (let offset: number = 0; offset < ids.length; offset += MAX_BATCH) {
            assertActive();
            const batch: string[] = ids.slice(offset, offset + MAX_BATCH);
            let profiles: readonly PublicProfile[];
            try {
                profiles = await this.fetchProfiles(batch, signal);
            } catch {
                assertActive();
                for (const id of batch) result.set(id, { status: 'unavailable' });
                continue;
            }
            assertActive();
            const names: Map<string, string> = new Map(profiles.map((profile: PublicProfile): [string, string] =>
                [profile.userId, profile.displayName]));
            for (const id of batch) {
                const name: string | undefined = names.get(id);
                result.set(id, name === undefined ? { status: 'missing' } : { status: 'resolved', displayName: name });
            }
        }
        return result;
    }
}

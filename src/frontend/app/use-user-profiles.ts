'use client';

import { useEffect, useState } from 'react';
import { useAuthenticatedApis } from './identity-provider';
import type { AuthenticatedApis, ProfileResolution } from '@/core/identity';

interface Result {
  readonly owner: AuthenticatedApis['profiles'];
  readonly key: string;
  readonly attempt: number;
  readonly values: ReadonlyMap<string, ProfileResolution>;
}

const empty: ReadonlyMap<string, ProfileResolution> = new Map();

/** Consumer-owned snapshot, shared by rows and confirmations within the view. */
export function useUserProfiles(userIds: readonly string[]) {
  const { profiles } = useAuthenticatedApis();
  const key = JSON.stringify([...new Set(userIds)].sort());
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Result>();
  useEffect(() => {
    const ids: string[] = JSON.parse(key);
    if (!ids.length) return;
    const controller = new AbortController();
    function publish(values: ReadonlyMap<string, ProfileResolution>) {
      if (controller.signal.aborted) return;
      setResult(previous => ({ owner: profiles, key, attempt, values: new Map(ids.map(id => {
        const incoming = values.get(id) ?? { status: 'unavailable' as const };
        const old = previous?.owner === profiles ? previous.values.get(id) : undefined;
        const name = old && old.status !== 'missing' ? old.displayName : undefined;
        return [id, incoming.status === 'unavailable' && incoming.displayName === undefined && name !== undefined
          ? { status: 'unavailable', displayName: name } : incoming];
      })) }));
    }
    void profiles.resolve(ids, controller.signal).then(publish, () => publish(empty));
    return () => controller.abort();
  }, [profiles, key, attempt]);
  const current = result?.owner === profiles ? result : undefined;
  const values = current?.key === key ? current.values : current ? new Map(userIds.flatMap(id => {
    const value = current.values.get(id);
    return value ? [[id, value] as const] : [];
  })) : empty;
  const loading = userIds.length > 0 && (current?.key !== key || current.attempt !== attempt);
  const unavailable = [...values.values()].some(value => value.status === 'unavailable');
  return { values, loading, unavailable, retry: () => setAttempt(current => current + 1) };
}

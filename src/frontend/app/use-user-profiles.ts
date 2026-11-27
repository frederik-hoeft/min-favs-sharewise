'use client';

import { useEffect, useState } from 'react';
import { useAuthenticatedApis } from './identity-provider';
import type { ProfileResolution } from '@/core/identity';

interface Result {
  readonly key: string;
  readonly attempt: number;
  readonly values: ReadonlyMap<string, ProfileResolution>;
}

const empty: ReadonlyMap<string, ProfileResolution> = new Map();

/** Consumer-level batch resolution; the identity runtime owns caching and request sharing. */
export function useUserProfiles(userIds: readonly string[]) {
  const { profiles } = useAuthenticatedApis();
  const key = JSON.stringify([...new Set(userIds)].sort());
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Result>();
  useEffect(() => {
    const ids: string[] = JSON.parse(key);
    if (!ids.length) return;
    const controller = new AbortController();
    void profiles.resolve(ids, controller.signal).then(values => {
      if (!controller.signal.aborted) setResult({ key, attempt, values });
    }, () => {
      if (!controller.signal.aborted) setResult({ key, attempt, values: new Map(ids.map(id => [id, { status: 'unavailable' }])) });
    });
    return () => controller.abort();
  }, [profiles, key, attempt]);
  const values = result?.key === key ? result.values : empty;
  const loading = userIds.length > 0 && (result?.key !== key || result.attempt !== attempt);
  const unavailable = [...values.values()].some(value => value.status === 'unavailable');
  return { values, loading, unavailable, retry: () => setAttempt(current => current + 1) };
}

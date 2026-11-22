import { useMemo } from 'react';
import { apiClient } from '@/lib/api';
import { GroupsApi } from './groups-api.ts';

/**
 * The single point that chooses the client for Group Management requests (P2.2a S5).
 *
 * Until browser authentication is available on this baseline it uses the plain client, so
 * requests are answered with `401` and pages show their sign-in-required state. With IAM's
 * identity runtime it supplies that runtime's authenticated client instead.
 */
export function useGroupsApi(): GroupsApi {
    const client = apiClient;
    return useMemo(() => new GroupsApi(client), [client]);
}

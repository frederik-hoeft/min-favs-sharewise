import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useUserProfiles } from '@/app/use-user-profiles';
import type { AuthenticatedApis, ProfileResolution } from '@/core/identity';
import { deferred } from './support';

const context = vi.hoisted(() => ({ profiles: undefined as AuthenticatedApis['profiles'] | undefined }));
vi.mock('@/app/identity-provider', () => ({ useAuthenticatedApis: () => ({ profiles: context.profiles }) }));

function resolved(ids: readonly string[]): ReadonlyMap<string, ProfileResolution> {
    return new Map(ids.map(id => [id, { status: 'resolved', displayName: `User ${id}` }]));
}
function unavailable(ids: readonly string[]): ReadonlyMap<string, ProfileResolution> {
    return new Map(ids.map(id => [id, { status: 'unavailable' }]));
}

describe('view-local profile snapshots', () => {
    it('retains names beyond the former cache capacity and for overlapping roster IDs, not authoritative omissions', async () => {
        const ids = Array.from({ length: 600 }, (_, i) => String(i));
        const resolve = vi.fn<AuthenticatedApis['profiles']['resolve']>()
            .mockImplementationOnce(async ids => resolved(ids))
            .mockImplementationOnce(async ids => unavailable(ids))
            .mockImplementationOnce(async ids => new Map(ids.map(id => [id, id === '0' ? { status: 'missing' } : { status: 'unavailable' }])))
            .mockImplementationOnce(async ids => unavailable(ids));
        context.profiles = { resolve };
        const { result, rerender } = renderHook(({ ids }) => useUserProfiles(ids), { initialProps: { ids } });
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.values.size).toBe(600);
        act(() => result.current.retry());
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.values.get('0')).toEqual({ status: 'unavailable', displayName: 'User 0' });
        rerender({ ids: ['0', '599', 'new'] });
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.values.get('0')).toEqual({ status: 'missing' });
        expect(result.current.values.get('599')).toEqual({ status: 'unavailable', displayName: 'User 599' });
        expect(result.current.values.get('new')).toEqual({ status: 'unavailable' });
        act(() => result.current.retry());
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.values.get('0')).toEqual({ status: 'unavailable' });
    });

    it('tracks a fresh same-roster lookup independently of the retained snapshot', async () => {
        const next = deferred<ReadonlyMap<string, ProfileResolution>>();
        const ids = ['a', 'b'];
        const resolve = vi.fn<AuthenticatedApis['profiles']['resolve']>()
            .mockResolvedValueOnce(new Map([
                ['a', { status: 'resolved', displayName: 'Previously observed' }],
                ['b', { status: 'unavailable' }],
            ]))
            .mockReturnValueOnce(next.promise);
        context.profiles = { resolve };
        const { result, rerender } = renderHook(({ ids }) => useUserProfiles(ids), { initialProps: { ids } });
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.unavailable).toBe(true);
        rerender({ ids: [] });
        expect(result.current.loading).toBe(false);
        expect(result.current.values.size).toBe(0);
        expect(resolve).toHaveBeenCalledTimes(1);
        rerender({ ids: ['b', 'a'] });
        expect(resolve).toHaveBeenCalledTimes(2);
        expect(result.current.loading).toBe(true);
        expect(result.current.values.get('a')).toEqual({ status: 'resolved', displayName: 'Previously observed' });
        await act(async () => next.resolve(resolved(ids)));
        expect(result.current.loading).toBe(false);
        expect(result.current.unavailable).toBe(false);
        expect(result.current.values).toEqual(resolved(ids));
        rerender({ ids });
        expect(resolve).toHaveBeenCalledTimes(2);
    });

    it('discards previous lifetime names and ignores a late superseded response', async () => {
        const late = deferred<ReadonlyMap<string, ProfileResolution>>();
        const resolve = vi.fn<AuthenticatedApis['profiles']['resolve']>()
            .mockImplementationOnce(async ids => resolved(ids)).mockReturnValueOnce(late.promise);
        context.profiles = { resolve };
        const { result, rerender } = renderHook(() => useUserProfiles(['a']));
        await waitFor(() => expect(result.current.loading).toBe(false));
        act(() => result.current.retry());
        await waitFor(() => expect(resolve).toHaveBeenCalledTimes(2));
        const oldSignal = resolve.mock.calls[1][1];
        context.profiles = { resolve: async ids => unavailable(ids) };
        rerender();
        expect(oldSignal?.aborted).toBe(true);
        expect(result.current.values.size).toBe(0);
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.values.get('a')).toEqual({ status: 'unavailable' });
        await act(async () => late.resolve(resolved(['a'])));
        expect(result.current.values.get('a')).toEqual({ status: 'unavailable' });
    });
});

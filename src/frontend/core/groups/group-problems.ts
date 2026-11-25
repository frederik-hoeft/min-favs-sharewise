import { ApiError } from '@/lib/api';
import { GROUP_PROBLEM_CODES } from './models.ts';

/**
 * Presentation-independent classification of a failed Group Management request. Pages decide
 * how each kind is displayed; the classification keeps that decision consistent across pages.
 */
export type GroupProblem =
    | { readonly kind: 'unauthenticated' }
    | { readonly kind: 'user_unknown' }
    | { readonly kind: 'not_found' }
    | { readonly kind: 'admin_required' }
    | { readonly kind: 'version_conflict' }
    | { readonly kind: 'invalid'; readonly error: ApiError }
    | { readonly kind: 'unavailable' };

export function classifyGroupProblem(error: unknown): GroupProblem {
    if (!(error instanceof ApiError)) {
        return { kind: 'unavailable' };
    }
    if (error.status === 401) {
        return { kind: 'unauthenticated' };
    }
    if (error.status === 403 && error.code === GROUP_PROBLEM_CODES.userUnknown) {
        return { kind: 'user_unknown' };
    }
    if (error.status === 403 && error.code === GROUP_PROBLEM_CODES.adminRequired) {
        return { kind: 'admin_required' };
    }
    if (error.status === 404) {
        return { kind: 'not_found' };
    }
    if (error.status === 409 && error.code === GROUP_PROBLEM_CODES.versionConflict) {
        return { kind: 'version_conflict' };
    }
    if (error.status === 400) {
        return { kind: 'invalid', error };
    }
    // Network failures, unreadable responses and server errors may succeed when retried.
    return { kind: 'unavailable' };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Group IDs are UUIDs; anything else cannot name a group and is treated as not found. */
export function isGroupId(value: string | null | undefined): value is string {
    return typeof value === 'string' && UUID.test(value);
}

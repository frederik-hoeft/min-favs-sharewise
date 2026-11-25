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
    | { readonly kind: 'member_not_found' }
    | { readonly kind: 'last_member' }
    | { readonly kind: 'last_administrator' }
    | { readonly kind: 'invalid_transfer' }
    | { readonly kind: 'invitation_unavailable' }
    | { readonly kind: 'invitation_not_found' }
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
    if (error.status === 404 && error.code === GROUP_PROBLEM_CODES.memberNotFound) {
        return { kind: 'member_not_found' };
    }
    if (error.status === 404 && error.code === GROUP_PROBLEM_CODES.invitationUnavailable) {
        return { kind: 'invitation_unavailable' };
    }
    if (error.status === 404 && error.code === GROUP_PROBLEM_CODES.invitationNotFound) {
        return { kind: 'invitation_not_found' };
    }
    if (error.status === 404) {
        return { kind: 'not_found' };
    }
    if (error.status === 409 && error.code === GROUP_PROBLEM_CODES.versionConflict) {
        return { kind: 'version_conflict' };
    }
    if (error.status === 409 && error.code === GROUP_PROBLEM_CODES.lastMember) {
        return { kind: 'last_member' };
    }
    if (error.status === 409 && error.code === GROUP_PROBLEM_CODES.lastAdministrator) {
        return { kind: 'last_administrator' };
    }
    if (error.status === 409 && error.code === GROUP_PROBLEM_CODES.invalidTransfer) {
        return { kind: 'invalid_transfer' };
    }
    if (error.status === 400) {
        return { kind: 'invalid', error };
    }
    // Network failures, unreadable responses and server errors may succeed when retried.
    return { kind: 'unavailable' };
}

/** A member's role within one group. The backend is authoritative for every role decision. */
export type GroupRole = 'admin' | 'member';

/** A list entry for one group of the requesting member. */
export interface GroupSummary {
    readonly id: string;
    readonly name: string;
    readonly description: string | null;
    readonly memberCount: number;
    readonly callerRole: GroupRole;
}

/** A group as seen by one of its members. */
export interface GroupDetails extends GroupSummary {
    /** ISO 8601 creation instant. */
    readonly createdAt: string;
    /** Metadata version; a replacement must name the version it was based on. */
    readonly version: number;
}

/** Wrapped so that pagination metadata can be added without breaking clients. */
export interface GroupList {
    readonly items: ReadonlyArray<GroupSummary>;
}

export interface CreateGroupRequest {
    readonly name: string;
    readonly description?: string | null;
}

/** Replaces the whole editable metadata; a `null` description clears it. */
export interface ReplaceGroupMetadataRequest {
    readonly name: string;
    readonly description: string | null;
    readonly expectedVersion: number;
}

/** Machine-readable Group Management problem codes. */
export const GROUP_PROBLEM_CODES = {
    userUnknown: 'group_user_unknown',
    notFound: 'group_not_found',
    adminRequired: 'group_admin_required',
    versionConflict: 'group_version_conflict',
    validationFailed: 'validation_failed',
} as const;

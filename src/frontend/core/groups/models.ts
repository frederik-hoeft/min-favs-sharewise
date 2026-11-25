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

export interface GroupMember {
    readonly id: string;
    readonly userId: string;
    readonly role: GroupRole;
    readonly joinedAt: string;
}

export interface GroupWorkspace {
    readonly group: GroupDetails;
    readonly callerMembershipId: string;
    readonly members: ReadonlyArray<GroupMember>;
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

/** Nonsecret invitation metadata; the bearer is available only in the creation response. */
export interface InvitationSummary {
    readonly id: string;
    readonly createdAt: string;
    readonly expiresAt: string | null;
    readonly maxUses: number | null;
    readonly useCount: number;
}

export interface InvitationList {
    readonly items: ReadonlyArray<InvitationSummary>;
}

export interface CreateInvitationRequest {
    readonly expiresAt: string | null;
    readonly maxUses: number | null;
}

export interface CreatedInvitation {
    readonly invitation: InvitationSummary;
    readonly token: string;
}

export interface InvitationRedemption {
    readonly groupId: string;
    readonly outcome: 'joined' | 'already_member';
}

/** Machine-readable Group Management problem codes. */
export const GROUP_PROBLEM_CODES = {
    userUnknown: 'group_user_unknown',
    notFound: 'group_not_found',
    adminRequired: 'group_admin_required',
    versionConflict: 'group_version_conflict',
    memberNotFound: 'group_member_not_found',
    lastMember: 'group_last_member',
    lastAdministrator: 'group_last_administrator',
    invalidTransfer: 'group_invalid_transfer',
    invitationUnavailable: 'group_invitation_unavailable',
    invitationNotFound: 'group_invitation_not_found',
    validationFailed: 'validation_failed',
} as const;

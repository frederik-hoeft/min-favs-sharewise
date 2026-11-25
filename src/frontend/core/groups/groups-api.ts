import type { ApiClient } from '@/lib/api';
import type {
    CreateGroupRequest,
    CreateInvitationRequest,
    CreatedInvitation,
    InvitationList,
    InvitationRedemption,
    GroupDetails,
    GroupList,
    GroupRole,
    GroupWorkspace,
    ReplaceGroupMetadataRequest,
} from './models.ts';

const GROUPS_PATH = '/groups/v1/groups';

/**
 * Endpoint adapter for Group Management. It owns paths and data contracts only; the injected
 * client decides how requests are authenticated and how failures become `ApiError` values.
 */
export class GroupsApi {
    private readonly client: ApiClient;

    constructor(client: ApiClient) {
        this.client = client;
    }

    list(): Promise<GroupList> {
        return this.client.get<GroupList>(GROUPS_PATH);
    }

    get(groupId: string): Promise<GroupDetails> {
        return this.client.get<GroupDetails>(groupPath(groupId));
    }

    getWorkspace(groupId: string): Promise<GroupWorkspace> {
        return this.client.get<GroupWorkspace>(`${groupPath(groupId)}/workspace`);
    }

    setMemberRole(groupId: string, membershipId: string, role: GroupRole): Promise<void> {
        return this.client.put<void>(`${memberPath(groupId, membershipId)}/role`, { role }, { expectedStatus: 204 });
    }

    removeMember(groupId: string, membershipId: string): Promise<void> {
        return this.client.delete<void>(memberPath(groupId, membershipId), { expectedStatus: 204 });
    }

    transferAdministration(groupId: string, membershipId: string): Promise<void> {
        return this.client.post<void>(`${memberPath(groupId, membershipId)}/transfer-administration`, undefined, { expectedStatus: 204 });
    }

    listInvitations(groupId: string): Promise<InvitationList> {
        return this.client.get<InvitationList>(`${groupPath(groupId)}/invitations`);
    }

    createInvitation(groupId: string, request: CreateInvitationRequest): Promise<CreatedInvitation> {
        return this.client.post<CreatedInvitation>(`${groupPath(groupId)}/invitations`, request, { expectedStatus: 201 });
    }

    revokeInvitation(groupId: string, invitationId: string): Promise<void> {
        return this.client.delete<void>(`${groupPath(groupId)}/invitations/${encodeURIComponent(invitationId)}`, { expectedStatus: 204 });
    }

    redeemInvitation(token: string): Promise<InvitationRedemption> {
        return this.client.post<InvitationRedemption>('/groups/v1/invitations/redeem', { token }, { expectedStatus: 200 });
    }

    create(request: CreateGroupRequest): Promise<GroupDetails> {
        return this.client.post<GroupDetails>(GROUPS_PATH, request);
    }

    replaceMetadata(groupId: string, request: ReplaceGroupMetadataRequest): Promise<GroupDetails> {
        return this.client.put<GroupDetails>(groupPath(groupId), request);
    }
}

function groupPath(groupId: string): string {
    return `${GROUPS_PATH}/${encodeURIComponent(groupId)}`;
}

function memberPath(groupId: string, membershipId: string): string {
    return `${groupPath(groupId)}/members/${encodeURIComponent(membershipId)}`;
}

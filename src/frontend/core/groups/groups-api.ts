import type { ApiClient } from '@/lib/api';
import type {
    CreateGroupRequest,
    GroupDetails,
    GroupList,
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

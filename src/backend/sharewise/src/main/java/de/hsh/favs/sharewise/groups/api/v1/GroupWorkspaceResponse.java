package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupWorkspace;
import java.util.List;
import java.util.UUID;

public record GroupWorkspaceResponse(GroupDetailsResponse group, UUID callerMembershipId, List<GroupMemberResponse> members) {
    static GroupWorkspaceResponse of(GroupWorkspace workspace) {
        return new GroupWorkspaceResponse(GroupDetailsResponse.of(workspace.group()), workspace.callerMembershipId(),
            workspace.members().stream().map(GroupMemberResponse::of).toList());
    }
}

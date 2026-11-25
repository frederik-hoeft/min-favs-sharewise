package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupMember;
import java.time.Instant;
import java.util.UUID;

public record GroupMemberResponse(UUID id, UUID userId, String role, Instant joinedAt) {
    static GroupMemberResponse of(GroupMember member) {
        return new GroupMemberResponse(member.id(), member.userId(), GroupRoles.toApi(member.role()), member.joinedAt());
    }
}

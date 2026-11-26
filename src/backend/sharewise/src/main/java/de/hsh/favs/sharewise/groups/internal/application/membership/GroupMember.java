package de.hsh.favs.sharewise.groups.internal.application.membership;

import de.hsh.favs.sharewise.groups.GroupRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A current organizational membership; its ID is distinct from the user's ID. */
public record GroupMember(UUID id, UUID userId, GroupRole role, Instant joinedAt) {
    public GroupMember {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}

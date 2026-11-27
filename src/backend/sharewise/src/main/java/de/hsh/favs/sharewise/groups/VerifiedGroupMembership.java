package de.hsh.favs.sharewise.groups;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Membership and role observed under the Group root lock at the specified instant. */
public record VerifiedGroupMembership(UUID userId, UUID groupId, GroupRole role, Instant verifiedAt) {
    public VerifiedGroupMembership {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }
}

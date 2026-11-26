package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Objects;
import java.util.UUID;

/**
 * A list entry for one group of the requesting member.
 *
 * @param id the group ID
 * @param metadata the current metadata
 * @param memberCount the number of current members
 * @param callerRole the requesting member's role
 */
public record GroupSummary(UUID id, GroupMetadata metadata, int memberCount, GroupRole callerRole) {

    public GroupSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(callerRole, "callerRole");
        if (memberCount < 1) {
            throw new IllegalArgumentException("memberCount must be positive");
        }
    }
}

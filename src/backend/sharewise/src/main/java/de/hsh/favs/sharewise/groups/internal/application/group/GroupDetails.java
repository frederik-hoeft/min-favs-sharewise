package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.GroupRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A group as seen by one of its members.
 *
 * @param id the group ID
 * @param name the stored name
 * @param description the stored description, or null
 * @param createdAt the creation instant
 * @param version the current metadata version
 * @param memberCount the number of current members
 * @param callerRole the requesting member's role
 */
public record GroupDetails(
        UUID id, String name, String description, Instant createdAt, long version, int memberCount, GroupRole callerRole) {

    public GroupDetails {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(callerRole, "callerRole");
        if (version < 0 || memberCount < 1) {
            throw new IllegalArgumentException("version must be non-negative and memberCount positive");
        }
    }
}

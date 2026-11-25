package de.hsh.favs.sharewise.groups.internal.application.access;

import java.util.Objects;
import java.util.UUID;

/**
 * A group whose row is locked for update in the current transaction segment.
 * Obtaining it is the first step of every mutation of an existing group.
 *
 * @param id the group ID
 * @param version the version read under the lock
 */
public record LockedGroup(UUID id, long version) {

    public LockedGroup {
        Objects.requireNonNull(id, "id");
    }
}

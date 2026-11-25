package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import java.util.Optional;
import java.util.UUID;

/**
 * Changes to existing groups under the group mutation protocol: lock the group row first,
 * then read current membership and apply the change in the same segment.
 */
public interface GroupMutationPort {

    /** Locks the group row for update; empty if the group does not exist. */
    Optional<LockedGroup> lockGroup(UUID groupId);

    /** Replaces the metadata, increments the version and returns the group as seen by the caller. */
    GroupDetails replaceMetadata(LockedGroup group, UUID callerId, GroupMetadata metadata);
}

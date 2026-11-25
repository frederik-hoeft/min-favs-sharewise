package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import java.util.UUID;

/**
 * Group metadata mutation under the group mutation protocol: lock the group row first,
 * then read current membership and apply the change in the same segment.
 */
public interface GroupMutationPort {

    /** Replaces the metadata, increments the version and returns the group as seen by the caller. */
    GroupDetails replaceMetadata(LockedGroup group, UUID callerId, GroupMetadata metadata);
}

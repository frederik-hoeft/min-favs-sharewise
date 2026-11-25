package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Optional;
import java.util.UUID;

/**
 * Changes to existing groups under the group mutation protocol: lock the group row first,
 * then read current membership and apply the change in the same segment.
 */
public interface GroupMutationPort {

    /** Locks the group row for update; empty if the group does not exist. */
    Optional<LockedGroup> lockGroup(UUID groupId);

    /** The user's current membership in the locked group; empty if the user is not a member. */
    Optional<GroupMember> memberForUser(LockedGroup group, UUID userId);

    Optional<GroupMember> memberById(LockedGroup group, UUID membershipId);

    /** Number of current memberships matching the role, or all roles if empty. */
    int countMembers(LockedGroup group, Optional<GroupRole> role);

    /** Changes exactly one current membership under the root lock. */
    void setRole(LockedGroup group, UUID membershipId, GroupRole role);

    /** Deletes exactly one current membership under the root lock. */
    void removeMember(LockedGroup group, UUID membershipId);

    /** Replaces the metadata, increments the version and returns the group as seen by the caller. */
    GroupDetails replaceMetadata(LockedGroup group, UUID callerId, GroupMetadata metadata);
}

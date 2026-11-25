package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Optional;
import java.util.UUID;

/** Membership reads and writes under an already acquired group-root lock. */
public interface GroupMembershipPort {

    /** The user's current membership in the locked group; empty if the user is not a member. */
    Optional<GroupMember> memberForUser(LockedGroup group, UUID userId);

    Optional<GroupMember> memberById(LockedGroup group, UUID membershipId);

    /** Number of current memberships matching the role, or all roles if empty. */
    int countMembers(LockedGroup group, Optional<GroupRole> role);

    /** Changes exactly one current membership under the root lock. */
    void setRole(LockedGroup group, UUID membershipId, GroupRole role);

    /** Inserts a new ordinary member under the root lock. */
    void addMember(LockedGroup group, UUID userId);

    /** Deletes exactly one current membership under the root lock. */
    void removeMember(LockedGroup group, UUID membershipId);

}

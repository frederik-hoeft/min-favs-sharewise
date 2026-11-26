package de.hsh.favs.sharewise.groups.internal.application.membership;

import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.GroupRole;
import java.util.Optional;
import java.util.UUID;

/** Membership reads and writes under an already acquired group-root lock. */
public interface GroupMembershipPort {

    /** The user's current membership in the locked group; empty if the user is not a member. */
    Optional<GroupMember> memberForUser(LockedGroup group, UUID userId);

    /** Caller and target facts from the same post-lock snapshot; empty for a non-member caller.
     * Non-administrators receive only their own target facts. */
    Optional<MembershipChangeContext> changeContext(LockedGroup group, UUID userId, UUID membershipId);

    /** Changes exactly one current membership under the root lock. */
    void setRole(LockedGroup group, UUID membershipId, GroupRole role);

    /** Inserts a new ordinary member under the root lock. */
    void addMember(LockedGroup group, UUID userId);

    /** Deletes exactly one current membership under the root lock. */
    void removeMember(LockedGroup group, UUID membershipId);

}

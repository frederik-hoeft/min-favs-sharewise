package de.hsh.favs.sharewise.groups.internal.application.membership;

import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Optional;
import java.util.UUID;

/**
 * Membership reads and writes under an already acquired group-root lock.
 *
 * <p>{@link #addMember} and {@link #removeMember} own the group's membership source revision:
 * each advances {@code membership_revision} by exactly one together with its membership row and
 * returns the new value. No other path advances it, and role changes leave it unchanged. Because
 * every caller holds the group row lock, concurrent changes of one group receive distinct,
 * consecutive revisions, and the returned value is the one committed with the membership change.
 */
public interface GroupMembershipPort {

    /** The user's current membership in the locked group; empty if the user is not a member. */
    Optional<GroupMember> memberForUser(LockedGroup group, UUID userId);

    /** Caller and target facts from the same post-lock snapshot; empty for a non-member caller.
     * Non-administrators receive only their own target facts. */
    Optional<MembershipChangeContext> changeContext(LockedGroup group, UUID userId, UUID membershipId);

    /** Changes exactly one current membership's role under the root lock; the revision is unchanged. */
    void setRole(LockedGroup group, UUID membershipId, GroupRole role);

    /**
     * Inserts a new ordinary member under the root lock and advances the membership revision.
     *
     * @return the group's new membership revision
     */
    long addMember(LockedGroup group, UUID userId);

    /**
     * Deletes exactly one current membership under the root lock and advances the membership revision.
     *
     * @return the group's new membership revision
     */
    long removeMember(LockedGroup group, UUID membershipId);

}

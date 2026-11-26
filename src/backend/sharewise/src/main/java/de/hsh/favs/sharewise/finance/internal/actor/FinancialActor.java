package de.hsh.favs.sharewise.finance.internal.actor;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a retained Finance actor and the last accepted Group membership revision
 * affecting it. The revision orders membership facts for this actor only; it is not a Finance revision.
 */
public record FinancialActor(UUID groupId, UUID userId, MembershipState state, long upstreamRevision) {

    public FinancialActor {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(state, "state");
        if (upstreamRevision <= 0) {
            throw new IllegalArgumentException("upstreamRevision must be positive");
        }
    }

    /** Whether both snapshots describe the same {@code (groupId, userId)} identity. */
    public boolean sameIdentity(FinancialActor other) {
        Objects.requireNonNull(other, "other");
        return groupId.equals(other.groupId) && userId.equals(other.userId);
    }
}

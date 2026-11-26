package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted Group-originated, full-state membership fact for one actor. The membership revision is
 * the per-actor idempotency and ordering key; it is not a Finance revision.
 *
 * @param groupId the upstream group identity
 * @param userId the member's user identity
 * @param membershipRevision the positive Group revision that produced this state
 * @param state the member's resulting state; required rather than defaulted
 */
public record ApplyMembershipChange(UUID groupId, UUID userId, long membershipRevision, MembershipState state) {

    public ApplyMembershipChange {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(state, "state");
        if (membershipRevision <= 0) {
            throw new IllegalArgumentException("membershipRevision must be positive");
        }
    }

    /** The actor as it would be retained if this fact is accepted. */
    public FinancialActor resultingActor() {
        return new FinancialActor(groupId, userId, state, membershipRevision);
    }
}

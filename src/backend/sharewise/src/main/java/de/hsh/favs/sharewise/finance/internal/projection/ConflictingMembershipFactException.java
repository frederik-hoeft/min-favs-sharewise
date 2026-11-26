package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import java.util.Objects;
import java.util.UUID;

/**
 * A membership fact carries the retained actor's revision but a different state, violating the
 * producer's one-state-per-revision contract. The retained actor is never overwritten, the
 * operation's segment rolls back, and the fact must not be acknowledged; recovery is an operator
 * decision.
 */
public final class ConflictingMembershipFactException extends RuntimeException {

    private final UUID groupId;
    private final UUID userId;
    private final long revision;
    private final MembershipState storedState;
    private final MembershipState receivedState;

    ConflictingMembershipFactException(FinancialActor stored, FinancialActor received) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(received, "received");
        if (!stored.sameIdentity(received) || stored.upstreamRevision() != received.upstreamRevision()
                || stored.state() == received.state()) {
            throw new IllegalArgumentException("A contradiction requires the same actor and revision with another state");
        }
        super("Conflicting membership fact for group " + stored.groupId() + ", user " + stored.userId()
                + " at revision " + stored.upstreamRevision() + ": stored " + stored.state()
                + ", received " + received.state());
        this.groupId = stored.groupId();
        this.userId = stored.userId();
        this.revision = stored.upstreamRevision();
        this.storedState = stored.state();
        this.receivedState = received.state();
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID userId() {
        return userId;
    }

    public long revision() {
        return revision;
    }

    public MembershipState storedState() {
        return storedState;
    }

    public MembershipState receivedState() {
        return receivedState;
    }
}

package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure per-actor ordering of full-state membership facts. The decision depends only on the
 * retained actor, if any, and the received fact; no group-level watermark participates, so a
 * higher revision of one actor never suppresses a lower revision of another.
 *
 * <table>
 *   <caption>Decisions</caption>
 *   <tr><th>Retained actor</th><th>Received revision</th><th>Received state</th><th>Decision</th></tr>
 *   <tr><td>absent</td><td>any</td><td>any</td><td>{@code Insert}</td></tr>
 *   <tr><td>present</td><td>greater</td><td>any</td><td>{@code Supersede}</td></tr>
 *   <tr><td>present</td><td>equal</td><td>equal</td><td>{@code Duplicate}</td></tr>
 *   <tr><td>present</td><td>equal</td><td>different</td><td>{@code Contradiction}</td></tr>
 *   <tr><td>present</td><td>less</td><td>any</td><td>{@code Stale}</td></tr>
 * </table>
 */
final class MembershipTransitionPolicy {

    private MembershipTransitionPolicy() {
    }

    /**
     * Decides how {@code change} affects the {@code retained} actor read for the same identity.
     *
     * @throws IllegalArgumentException if the retained actor has another identity than the change
     */
    static MembershipTransition decide(Optional<FinancialActor> retained, ApplyMembershipChange change) {
        Objects.requireNonNull(retained, "retained");
        Objects.requireNonNull(change, "change");
        FinancialActor received = change.resultingActor();
        if (retained.isEmpty()) {
            return new MembershipTransition.Insert(received);
        }
        FinancialActor current = retained.get();
        if (!current.sameIdentity(received)) {
            throw new IllegalArgumentException("Retained actor does not belong to the membership change");
        }
        int order = Long.compare(received.upstreamRevision(), current.upstreamRevision());
        if (order > 0) {
            return new MembershipTransition.Supersede(current, received);
        }
        if (order < 0) {
            return new MembershipTransition.Stale(current);
        }
        return current.state() == received.state()
                ? new MembershipTransition.Duplicate(current)
                : new MembershipTransition.Contradiction(current, received);
    }
}

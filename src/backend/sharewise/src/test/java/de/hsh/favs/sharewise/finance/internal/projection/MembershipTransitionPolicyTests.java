package de.hsh.favs.sharewise.finance.internal.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every row of the per-actor membership transition table (PRJ-T01). */
class MembershipTransitionPolicyTests {

    private static final UUID GROUP = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final List<MembershipState> STATES = List.of(MembershipState.values());

    /** Ordered (lower, higher) revision pairs, including both boundaries of the valid range. */
    private static final List<long[]> ORDERED = List.of(
            new long[] {1, 2}, new long[] {1, Long.MAX_VALUE}, new long[] {Long.MAX_VALUE - 1, Long.MAX_VALUE});

    @Test
    void unknownActorIsInsertedWithTheReceivedStateAndRevision() {
        for (MembershipState state : STATES) {
            for (long revision : new long[] {1, 7, Long.MAX_VALUE}) {
                ApplyMembershipChange change = change(revision, state);
                assertEquals(new MembershipTransition.Insert(actor(revision, state)), decide(Optional.empty(), change));
            }
        }
    }

    @Test
    void greaterRevisionSupersedesRegardlessOfEitherState() {
        for (long[] pair : ORDERED) {
            for (MembershipState stored : STATES) {
                for (MembershipState received : STATES) {
                    FinancialActor current = actor(pair[0], stored);
                    assertEquals(new MembershipTransition.Supersede(current, actor(pair[1], received)),
                            decide(Optional.of(current), change(pair[1], received)));
                }
            }
        }
    }

    @Test
    void equalRevisionAndStateIsADuplicate() {
        for (long revision : new long[] {1, Long.MAX_VALUE}) {
            for (MembershipState state : STATES) {
                FinancialActor current = actor(revision, state);
                assertEquals(new MembershipTransition.Duplicate(current),
                        decide(Optional.of(current), change(revision, state)));
            }
        }
    }

    @Test
    void equalRevisionWithAnotherStateIsAContradiction() {
        for (long revision : new long[] {1, Long.MAX_VALUE}) {
            for (MembershipState stored : STATES) {
                MembershipState received = other(stored);
                FinancialActor current = actor(revision, stored);
                assertEquals(new MembershipTransition.Contradiction(current, actor(revision, received)),
                        decide(Optional.of(current), change(revision, received)));
            }
        }
    }

    @Test
    void lowerRevisionIsStaleRegardlessOfEitherState() {
        for (long[] pair : ORDERED) {
            for (MembershipState stored : STATES) {
                for (MembershipState received : STATES) {
                    FinancialActor current = actor(pair[1], stored);
                    assertEquals(new MembershipTransition.Stale(current),
                            decide(Optional.of(current), change(pair[0], received)));
                }
            }
        }
    }

    @Test
    void decisionIsAFunctionOfItsInputsOnly() {
        FinancialActor current = actor(3, MembershipState.ACTIVE);
        ApplyMembershipChange change = change(4, MembershipState.REMOVED);
        assertEquals(decide(Optional.of(current), change), decide(Optional.of(current), change));
        assertEquals(decide(Optional.empty(), change), decide(Optional.empty(), change));
    }

    @Test
    void retainedActorOfAnotherIdentityIsRejected() {
        ApplyMembershipChange change = change(2, MembershipState.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> decide(
                Optional.of(new FinancialActor(GROUP, UUID.randomUUID(), MembershipState.ACTIVE, 1)), change));
        assertThrows(IllegalArgumentException.class, () -> decide(
                Optional.of(new FinancialActor(UUID.randomUUID(), USER, MembershipState.ACTIVE, 1)), change));
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> decide(null, change(1, MembershipState.ACTIVE)));
        assertThrows(NullPointerException.class, () -> decide(Optional.empty(), null));
    }

    private static MembershipTransition decide(Optional<FinancialActor> retained, ApplyMembershipChange change) {
        return MembershipTransitionPolicy.decide(retained, change);
    }

    private static ApplyMembershipChange change(long revision, MembershipState state) {
        return new ApplyMembershipChange(GROUP, USER, revision, state);
    }

    private static FinancialActor actor(long revision, MembershipState state) {
        return new FinancialActor(GROUP, USER, state, revision);
    }

    private static MembershipState other(MembershipState state) {
        return state == MembershipState.ACTIVE ? MembershipState.REMOVED : MembershipState.ACTIVE;
    }
}

package de.hsh.favs.sharewise.finance.internal.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Command validation (PRJ-T02) and the contradiction diagnostics. */
class FinanceProjectionCommandTests {

    private static final UUID GROUP = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void activationRequiresAGroup() {
        assertEquals(GROUP, new ActivateFinancialGroup(GROUP).groupId());
        assertThrows(NullPointerException.class, () -> new ActivateFinancialGroup(null));
    }

    @Test
    void membershipChangeRejectsMissingFields() {
        assertThrows(NullPointerException.class, () -> new ApplyMembershipChange(null, USER, 1, MembershipState.ACTIVE));
        assertThrows(NullPointerException.class, () -> new ApplyMembershipChange(GROUP, null, 1, MembershipState.ACTIVE));
        assertThrows(NullPointerException.class, () -> new ApplyMembershipChange(GROUP, USER, 1, null));
    }

    @Test
    void membershipChangeRequiresAPositiveRevisionUpToTheMaximum() {
        for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ApplyMembershipChange(GROUP, USER, invalid, MembershipState.ACTIVE));
        }
        assertEquals(1, new ApplyMembershipChange(GROUP, USER, 1, MembershipState.REMOVED).membershipRevision());
        assertEquals(new FinancialActor(GROUP, USER, MembershipState.REMOVED, Long.MAX_VALUE),
                new ApplyMembershipChange(GROUP, USER, Long.MAX_VALUE, MembershipState.REMOVED).resultingActor());
    }

    @Test
    void contradictionCarriesTheConflictingFactAndRetainedState() {
        ConflictingMembershipFactException failure = new ConflictingMembershipFactException(
                new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 5),
                new FinancialActor(GROUP, USER, MembershipState.REMOVED, 5));
        assertEquals(GROUP, failure.groupId());
        assertEquals(USER, failure.userId());
        assertEquals(5, failure.revision());
        assertEquals(MembershipState.ACTIVE, failure.storedState());
        assertEquals(MembershipState.REMOVED, failure.receivedState());
        assertEquals("Conflicting membership fact for group " + GROUP + ", user " + USER
                + " at revision 5: stored ACTIVE, received REMOVED", failure.getMessage());
    }

    @Test
    void contradictionRequiresTheSameActorAndRevisionWithAnotherState() {
        FinancialActor stored = new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 5);
        assertThrows(IllegalArgumentException.class, () -> new ConflictingMembershipFactException(stored, stored));
        assertThrows(IllegalArgumentException.class, () -> new ConflictingMembershipFactException(stored,
                new FinancialActor(GROUP, USER, MembershipState.REMOVED, 6)));
        assertThrows(IllegalArgumentException.class, () -> new ConflictingMembershipFactException(stored,
                new FinancialActor(GROUP, UUID.randomUUID(), MembershipState.REMOVED, 5)));
        assertThrows(NullPointerException.class, () -> new ConflictingMembershipFactException(null, stored));
        assertThrows(NullPointerException.class, () -> new ConflictingMembershipFactException(stored, null));
    }
}

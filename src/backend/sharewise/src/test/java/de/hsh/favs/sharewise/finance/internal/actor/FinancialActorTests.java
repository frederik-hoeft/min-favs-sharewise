package de.hsh.favs.sharewise.finance.internal.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialActorTests {

    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void snapshotRequiresIdentityStateAndPositiveRevision() {
        assertThrows(NullPointerException.class, () -> new FinancialActor(null, USER, MembershipState.ACTIVE, 1));
        assertThrows(NullPointerException.class, () -> new FinancialActor(GROUP, null, MembershipState.ACTIVE, 1));
        assertThrows(NullPointerException.class, () -> new FinancialActor(GROUP, USER, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FinancialActor(GROUP, USER, MembershipState.REMOVED, -1));
        assertEquals(1L, new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 1).upstreamRevision());
        assertEquals(Long.MAX_VALUE,
                new FinancialActor(GROUP, USER, MembershipState.REMOVED, Long.MAX_VALUE).upstreamRevision());
    }

    @Test
    void identityIgnoresStateAndRevision() {
        FinancialActor actor = new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 1);
        assertTrue(actor.sameIdentity(new FinancialActor(GROUP, USER, MembershipState.REMOVED, 7)));
        assertFalse(actor.sameIdentity(new FinancialActor(UUID.randomUUID(), USER, MembershipState.ACTIVE, 1)));
        assertFalse(actor.sameIdentity(new FinancialActor(GROUP, UUID.randomUUID(), MembershipState.ACTIVE, 1)));
        assertThrows(NullPointerException.class, () -> actor.sameIdentity(null));
    }
}

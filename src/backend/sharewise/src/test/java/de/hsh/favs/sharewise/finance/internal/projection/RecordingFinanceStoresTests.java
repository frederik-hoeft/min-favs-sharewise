package de.hsh.favs.sharewise.finance.internal.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The recording segment owner reproduces the production segment disposition it substitutes. */
class RecordingFinanceStoresTests {

    private static final UUID GROUP = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final RecordingFinanceStores stores = new RecordingFinanceStores();

    @Test
    void committingARollbackOnlySegmentFailsLikeSpringTransactionsAndRestoresTheStartState() {
        FinancialGroup seeded = new FinancialGroup(GROUP, FinancialGroupState.PROVISIONING, 6);
        stores.seedRoot(seeded);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> stores.transactions().execute(() -> {
                    stores.groups().activate(stores.groups().ensureAndLock(GROUP));
                    stores.actors().insert(new FinancialActor(GROUP, USER, MembershipState.ACTIVE, 1));
                    stores.transactions().markRollbackOnly();
                    return TransactionOutcome.commit("must not be returned");
                }));

        assertEquals("Cannot commit a rollback-only transaction segment", failure.getMessage());
        assertEquals(List.of("begin", "groups.ensureAndLock", "groups.activate", "actors.insert", "failed"),
                stores.journal());
        assertEquals(Optional.of(seeded), stores.root(GROUP));
        assertEquals(0, stores.actorCount());
    }
}

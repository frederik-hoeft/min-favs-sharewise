package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Actors.ACTORS;
import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Groups.GROUPS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.enums.GroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.GroupsRecord;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.InvalidDataAccessApiUsageException;

@Tag("integration")
class JooqFinancialGroupStoreIT extends IntegrationTestBase {

    @Test
    void provisionsOnlyTheRootAndDistinguishesMissingFromProvisioning() {
        runServiceTest(FinancialGroupStore.class, (store, scope) -> {
            UUID id = UUID.randomUUID();
            UUID missing = UUID.randomUUID();
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext db = scope.resolve(JooqAccess.class).dsl();
                assertEquals(Optional.empty(), store.lockExisting(id));
                FinancialGroup expected = new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0);
                assertEquals(expected, store.ensureAndLock(id));
                assertEquals(Optional.of(expected), store.lockExisting(id));
                assertEquals(expected, store.ensureAndLock(id));
                assertEquals(Optional.empty(), store.lockExisting(missing));
                assertEquals(1, db.fetchCount(GROUPS, GROUPS.ID.in(id, missing)));
                assertEquals(0, db.fetchCount(ACTORS, ACTORS.GROUP_ID.eq(id)));
                return TransactionOutcome.abstain(null);
            });
        });
    }

    @Test
    void preservesEveryExistingLifecycleRevisionTimestampAndRowVersion() {
        runServiceTest(FinancialGroupStore.class, (store, scope) -> {
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext db = scope.resolve(JooqAccess.class).dsl();
                OffsetDateTime created = OffsetDateTime.parse("2026-01-01T12:00:00Z");
                for (FinancialGroupState state : FinancialGroupState.values()) {
                    UUID id = UUID.randomUUID();
                    GroupState persisted = switch (state) {
                        case PROVISIONING -> GroupState.provisioning;
                        case OPEN -> GroupState.open;
                        case CLOSED -> GroupState.closed;
                        case ARCHIVED -> GroupState.archived;
                    };
                    db.insertInto(GROUPS).columns(GROUPS.ID, GROUPS.STATE, GROUPS.CURRENT_REVISION, GROUPS.CREATED_AT)
                            .values(id, persisted, Long.MAX_VALUE, created).execute();
                    String tuple = tupleId(db, id);
                    FinancialGroup expected = new FinancialGroup(id, state, Long.MAX_VALUE);
                    assertEquals(expected, store.ensureAndLock(id));
                    assertEquals(expected, store.ensureAndLock(id));
                    assertEquals(Optional.of(expected), store.lockExisting(id));
                    GroupsRecord row = db.selectFrom(GROUPS).where(GROUPS.ID.eq(id)).fetchSingle();
                    assertEquals(persisted, row.getState());
                    assertEquals(Long.MAX_VALUE, row.getCurrentRevision());
                    assertEquals(created.toInstant(), row.getCreatedAt().toInstant());
                    // PostgreSQL gives UPDATE a new tuple version, even for unchanged values.
                    assertEquals(tuple, tupleId(db, id), "Existing root must not be updated");
                }
                return TransactionOutcome.abstain(null);
            });
        });
    }

    @Test
    void rejectsNullIdsAndRequiresAnActiveLogicalSegment() {
        runServiceTest(FinancialGroupStore.class, (store, scope) -> {
            UUID id = UUID.randomUUID();
            assertNoActiveSegment(() -> store.ensureAndLock(id));
            assertNoActiveSegment(() -> store.lockExisting(id));
            scope.resolve(Transactions.class).execute(() -> {
                assertThrows(NullPointerException.class, () -> store.ensureAndLock(null));
                assertThrows(NullPointerException.class, () -> store.lockExisting(null));
                assertTrue(store.lockExisting(id).isEmpty(), "Rejected access must not have inserted a root");
                return TransactionOutcome.abstain(null);
            });
            assertNoActiveSegment(() -> store.ensureAndLock(id));
            assertNoActiveSegment(() -> store.lockExisting(id));
        });
    }

    private static void assertNoActiveSegment(Executable operation) {
        // The real @Repository proxy translates the transaction guard exception.
        InvalidDataAccessApiUsageException failure = assertThrows(InvalidDataAccessApiUsageException.class, operation);
        assertEquals("No active logical transaction segment",
                assertInstanceOf(IllegalStateException.class, failure.getCause()).getMessage());
    }

    private static String tupleId(DSLContext db, UUID id) {
        return db.fetchSingle("select ctid::text from finance.groups where id = ?", id).get(0, String.class);
    }
}

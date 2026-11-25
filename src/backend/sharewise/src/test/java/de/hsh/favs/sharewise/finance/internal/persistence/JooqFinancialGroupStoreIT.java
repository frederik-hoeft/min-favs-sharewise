package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Actors.ACTORS;
import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Groups.GROUPS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupFailure;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupLookup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.enums.GroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.GroupsRecord;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
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
                FinancialGroupLookup absent = store.lockExisting(id);
                assertEquals(Optional.empty(), absent.group());
                assertEquals(Result.err(FinancialGroupFailure.NOT_READY), absent.requireReady());
                FinancialGroup expected = new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0);
                assertEquals(expected, store.ensureAndLock(id));
                FinancialGroupLookup provisioning = store.lockExisting(id);
                assertEquals(Optional.of(expected), provisioning.group());
                assertEquals(Result.err(FinancialGroupFailure.NOT_READY), provisioning.requireReady());
                assertEquals(expected, store.ensureAndLock(id));
                assertEquals(Optional.empty(), store.lockExisting(missing).group());
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
                    FinancialGroupLookup lookup = store.lockExisting(id);
                    assertEquals(Optional.of(expected), lookup.group());
                    Result<FinancialGroup, FinancialGroupFailure> readiness = state == FinancialGroupState.PROVISIONING
                            ? Result.err(FinancialGroupFailure.NOT_READY) : Result.ok(expected);
                    assertEquals(readiness, lookup.requireReady());
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
                assertTrue(store.lockExisting(id).group().isEmpty(), "Rejected access must not have inserted a root");
                return TransactionOutcome.abstain(null);
            });
            assertNoActiveSegment(() -> store.ensureAndLock(id));
            assertNoActiveSegment(() -> store.lockExisting(id));
        });
    }

    @Test
    void activationOpensAProvisioningRootWithoutChangingRevisionCreationOrActors() {
        runServiceTest(FinancialGroupStore.class, (store, scope) -> {
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext db = scope.resolve(JooqAccess.class).dsl();
                UUID id = UUID.randomUUID();
                OffsetDateTime created = OffsetDateTime.parse("2026-01-01T12:00:00Z");
                db.insertInto(GROUPS).columns(GROUPS.ID, GROUPS.CURRENT_REVISION, GROUPS.CREATED_AT)
                        .values(id, 5L, created).execute();
                FinancialGroup locked = store.ensureAndLock(id);
                FinancialGroup opened = new FinancialGroup(id, FinancialGroupState.OPEN, 5);
                assertEquals(opened, store.activate(locked));
                assertEquals(opened, store.lockExisting(id).group().orElseThrow());
                GroupsRecord row = db.selectFrom(GROUPS).where(GROUPS.ID.eq(id)).fetchSingle();
                assertEquals(GroupState.open, row.getState());
                assertEquals(created.toInstant(), row.getCreatedAt().toInstant());
                assertEquals(0, db.fetchCount(ACTORS, ACTORS.GROUP_ID.eq(id)));
                return TransactionOutcome.abstain(null);
            });
        });
    }

    @Test
    void activationRejectsNonProvisioningSnapshotsAndStaleSnapshotsWithoutWriting() {
        runServiceTest(FinancialGroupStore.class, (store, scope) -> {
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext db = scope.resolve(JooqAccess.class).dsl();
                for (FinancialGroupState state : FinancialGroupState.values()) {
                    if (state == FinancialGroupState.PROVISIONING) {
                        continue;
                    }
                    UUID id = UUID.randomUUID();
                    db.insertInto(GROUPS).columns(GROUPS.ID, GROUPS.STATE).values(id, persisted(state)).execute();
                    FinancialGroup locked = store.ensureAndLock(id);
                    String tuple = tupleId(db, id);
                    assertTranslated(IllegalArgumentException.class, () -> store.activate(locked));
                    // A snapshot claiming provisioning must not reopen a stored root in another state.
                    assertTranslated(IllegalStateException.class,
                            () -> store.activate(new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0)));
                    assertEquals(locked, store.lockExisting(id).group().orElseThrow());
                    assertEquals(tuple, tupleId(db, id), "Rejected activation must not update the root");
                }
                UUID missing = UUID.randomUUID();
                assertTranslated(IllegalStateException.class,
                        () -> store.activate(new FinancialGroup(missing, FinancialGroupState.PROVISIONING, 0)));
                assertTrue(store.lockExisting(missing).group().isEmpty());
                assertThrows(NullPointerException.class, () -> store.activate(null));
                return TransactionOutcome.abstain(null);
            });
        });
    }

    private static GroupState persisted(FinancialGroupState state) {
        return switch (state) {
            case PROVISIONING -> GroupState.provisioning;
            case OPEN -> GroupState.open;
            case CLOSED -> GroupState.closed;
            case ARCHIVED -> GroupState.archived;
        };
    }

    /** The real {@code @Repository} proxy translates argument and state exceptions, keeping the original cause. */
    private static void assertTranslated(Class<? extends RuntimeException> expected, Executable operation) {
        InvalidDataAccessApiUsageException failure = assertThrows(InvalidDataAccessApiUsageException.class, operation);
        assertInstanceOf(expected, failure.getCause());
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

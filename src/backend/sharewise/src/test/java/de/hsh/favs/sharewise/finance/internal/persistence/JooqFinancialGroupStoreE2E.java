package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Groups.GROUPS;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.await;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.awaitBlockedBy;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.backendPid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.enums.GroupState;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real physical transaction and PostgreSQL row-lock behavior of the Finance root port. */
@Tag("e2e")
class JooqFinancialGroupStoreE2E extends E2ETestBase {

    @Test
    void concurrentFirstProvisioningWaitsForTheCommittedWinner() {
        runFinanceTest(fixture -> assertConcurrentProvisioning(fixture, false));
    }

    @Test
    void concurrentFirstProvisioningCanReplaceARolledBackInsert() {
        runFinanceTest(fixture -> assertConcurrentProvisioning(fixture, true));
    }

    private static void assertConcurrentProvisioning(IntegrationTestContext fixture, boolean rollback) {
        UUID id = UUID.randomUUID();
        FinancialGroup initial = new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Integer> contenderPid = new CompletableFuture<>();
            Future<FinancialGroup> contender = inSegment(fixture, (store, db) -> {
                assertEquals(initial, store.ensureAndLock(id));
                Future<FinancialGroup> pending = workers.submit(() -> inSegment(fixture, (other, otherDb) -> {
                    contenderPid.complete(backendPid(otherDb));
                    return TransactionOutcome.commit(other.ensureAndLock(id));
                }));
                awaitBlockedBy(db, contenderPid, pending);
                if (rollback) {
                    // A failed first transaction must not leak any of its changes to the retrying contender.
                    assertEquals(1, db.update(GROUPS).set(GROUPS.CURRENT_REVISION, 17L)
                            .where(GROUPS.ID.eq(id)).execute());
                    return TransactionOutcome.rollback(pending);
                }
                return TransactionOutcome.commit(pending);
            });
            assertEquals(initial, await(contender));
            assertCommittedRoot(fixture, initial);
        }
    }

    @Test
    void existingRootLockSurvivesAdapterReturnAndWaiterReadsCommittedRevision() {
        runFinanceTest(fixture -> assertExistingRootSerialization(fixture, false, false));
    }

    @Test
    void rollbackRestoresExistingRootAndReleasesTheLock() {
        runFinanceTest(fixture -> assertExistingRootSerialization(fixture, true, false));
    }

    @Test
    void ensureOnExistingRootAlsoHoldsItsRowLockUntilCommit() {
        runFinanceTest(fixture -> assertExistingRootSerialization(fixture, false, true));
    }

    private static void assertExistingRootSerialization(IntegrationTestContext fixture, boolean rollback,
            boolean ensureOwner) {
        UUID id = UUID.randomUUID();
        FinancialGroup initial = seedOpenRoot(fixture, id);
        FinancialGroup expected = new FinancialGroup(id, FinancialGroupState.OPEN, rollback ? 3 : 4);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Integer> contenderPid = new CompletableFuture<>();
            Future<FinancialGroup> contender = inSegment(fixture, (store, db) -> {
                if (ensureOwner) {
                    assertEquals(initial, store.ensureAndLock(id));
                } else {
                    assertEquals(Optional.of(initial), store.lockExisting(id).group());
                }
                // Start the competing SELECT before any UPDATE could acquire a lock itself.
                Future<FinancialGroup> pending = workers.submit(() -> inSegment(fixture, (other, otherDb) -> {
                    contenderPid.complete(backendPid(otherDb));
                    return TransactionOutcome.commit(other.lockExisting(id).group().orElseThrow());
                }));
                awaitBlockedBy(db, contenderPid, pending);
                assertEquals(1, db.update(GROUPS).set(GROUPS.CURRENT_REVISION, 4L)
                        .where(GROUPS.ID.eq(id)).execute());
                return rollback ? TransactionOutcome.rollback(pending) : TransactionOutcome.commit(pending);
            });
            assertEquals(expected, await(contender));
            assertCommittedRoot(fixture, expected);
        }
    }

    @Test
    void unrelatedRootCanBeProvisionedAndCommittedWhileFirstRootIsLocked() {
        runFinanceTest(fixture -> {
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            FinancialGroup first = seedOpenRoot(fixture, firstId);
            FinancialGroup second = new FinancialGroup(secondId, FinancialGroupState.PROVISIONING, 0);
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                inSegment(fixture, (store, db) -> {
                    assertEquals(Optional.of(first), store.lockExisting(firstId).group());
                    int firstPid = backendPid(db);
                    Future<FinancialGroup> pending = workers.submit(() -> inSegment(fixture, (other, otherDb) -> {
                        assertNotEquals(firstPid, backendPid(otherDb), "Operations must use independent connections");
                        FinancialGroup ensured = other.ensureAndLock(secondId);
                        assertEquals(Optional.of(ensured), other.lockExisting(secondId).group());
                        return TransactionOutcome.commit(ensured);
                    }));
                    assertEquals(second, await(pending));
                    // The second commit is visible without releasing the first lock.
                    assertEquals(1, db.fetchCount(GROUPS, GROUPS.ID.eq(secondId)));
                    return TransactionOutcome.commit(null);
                });
            }
            assertCommittedRoot(fixture, first);
            assertCommittedRoot(fixture, second);
        });
    }

    @Test
    void rollbackOfFirstProvisioningLeavesNoCommittedRoot() {
        runFinanceTest(fixture -> {
            UUID id = UUID.randomUUID();
            inSegment(fixture, (store, db) -> {
                assertEquals(new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0), store.ensureAndLock(id));
                return TransactionOutcome.rollback(null);
            });
            inSegment(fixture, (store, db) -> {
                assertEquals(Optional.empty(), store.lockExisting(id).group());
                assertEquals(0, db.fetchCount(GROUPS, GROUPS.ID.eq(id)));
                return TransactionOutcome.commit(null);
            });
        });
    }

    @Test
    void lockFailurePropagatesInsteadOfReportingAbsence() {
        runFinanceTest(fixture -> {
            UUID id = UUID.randomUUID();
            FinancialGroup initial = seedOpenRoot(fixture, id);
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                inSegment(fixture, (store, db) -> {
                    assertEquals(Optional.of(initial), store.lockExisting(id).group());
                    Future<RuntimeException> pending = workers.submit(() -> assertThrows(RuntimeException.class,
                            () -> inSegment(fixture, (other, otherDb) -> {
                                otherDb.execute("set local lock_timeout = '250ms'");
                                return TransactionOutcome.commit(other.lockExisting(id));
                            })));
                    // PostgreSQL lock_not_available, not an absent-root result or a generic scope failure.
                    assertEquals("55P03", sqlState(await(pending)));
                    return TransactionOutcome.commit(null);
                });
            }
            assertCommittedRoot(fixture, initial);
        });
    }

    private void runFinanceTest(TestCallbacks.Action<IntegrationTestContext> test) {
        runTest(test, ProductionEntityScanConfiguration.class);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlFailure) {
                return sqlFailure.getSQLState();
            }
        }
        throw new AssertionError("Expected a PostgreSQL failure", failure);
    }

    private static FinancialGroup seedOpenRoot(IntegrationTestContext fixture, UUID id) {
        return inSegment(fixture, (store, db) -> {
            db.insertInto(GROUPS).columns(GROUPS.ID, GROUPS.STATE, GROUPS.CURRENT_REVISION)
                    .values(id, GroupState.open, 3L).execute();
            return TransactionOutcome.commit(new FinancialGroup(id, FinancialGroupState.OPEN, 3));
        });
    }

    private static void assertCommittedRoot(IntegrationTestContext fixture, FinancialGroup expected) {
        inSegment(fixture, (store, db) -> {
            assertEquals(Optional.of(expected), store.lockExisting(expected.id()).group());
            assertEquals(1, db.fetchCount(GROUPS, GROUPS.ID.eq(expected.id())));
            return TransactionOutcome.commit(null);
        });
    }

    private static <T> T inSegment(IntegrationTestContext fixture,
            BiFunction<FinancialGroupStore, DSLContext, TransactionOutcome<T>> callback) {
        return new OperationTestHarness(fixture.context().getBean(ServiceScopeFactory.class))
                .inOperationScope(scope -> scope.resolve(Transactions.class).execute(() -> {
                    DSLContext db = scope.resolve(JooqAccess.class).dsl();
                    // Bound SQL waits even when an assertion fails before the other transaction completes.
                    db.execute("set local statement_timeout = '15s'");
                    return callback.apply(scope.resolve(FinancialGroupStore.class), db);
                }));
    }
}

package de.hsh.favs.sharewise.finance.internal.projection;

import static de.hsh.favs.sharewise.finance.internal.projection.ProjectionDispatch.activate;
import static de.hsh.favs.sharewise.finance.internal.projection.ProjectionDispatch.apply;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.WAIT;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.await;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.awaitLockWaiters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupLookup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.ActorsRecord;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.GroupsRecord;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.BoundedStatementsConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/**
 * Concurrent projection operations serialize on their group's root lock only, and the source
 * revision rather than {@code updated_at} orders them (PRJ-T11 to PRJ-T13, PRJ-T19, PRJ-T20).
 * Operations run as independent production dispatches with their own connections; a fixture
 * transaction on a further connection holds the contended lock, and the tests advance only after
 * PostgreSQL reports the expected lock waits. Holders are declared after their executors, so
 * they are released before workers are joined, including on assertion failure.
 */
@Tag("e2e")
class FinanceProjectionConcurrencyE2E extends E2ETestBase {

    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    @Test
    void concurrentFirstObservationsOfAGroupConvergeOnOneOpenRootWithBothActors() {
        runConcurrencyTest(fixture -> {
            UUID group = UUID.randomUUID();
            List<Object> outcomes;
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
                 Connection holder = PostgresLockWaits.holdLocks(fixture,
                         "INSERT INTO finance.groups (id) VALUES (?)", group)) {
                // All three provisioning inserts wait on the holder's uncommitted root.
                Future<MembershipOutcome> first = workers.submit(() ->
                        apply(fixture, group, FIRST, 1, MembershipState.ACTIVE));
                Future<MembershipOutcome> second = workers.submit(() ->
                        apply(fixture, group, SECOND, 2, MembershipState.ACTIVE));
                Future<ActivationOutcome> activation = workers.submit(() -> activate(fixture, group));
                awaitLockWaiters(fixture, holder, 3, WAIT);
                // Rolling back leaves the group without a root, so the operations race to provision it.
                holder.rollback();
                outcomes = List.of(await(first), await(second), await(activation));
            }

            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.INSERTED, ActivationOutcome.ACTIVATED),
                    outcomes);
            assertEquals(Optional.of(new FinancialGroup(group, FinancialGroupState.OPEN, 0)),
                    FinanceTestFixture.root(fixture, group));
            assertEquals(List.of(new FinancialActor(group, FIRST, MembershipState.ACTIVE, 1),
                    new FinancialActor(group, SECOND, MembershipState.ACTIVE, 2)),
                    FinanceTestFixture.actors(fixture, group));
            assertEquals(1, FinanceTestFixture.committedState(fixture).roots().size());
        });
    }

    @Test
    void sameActorFactsQueuedOnTheRootLockApplyInQueueOrderAndConvergeOnTheHighestRevision() {
        runConcurrencyTest(fixture -> {
            UUID ascending = UUID.randomUUID();
            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.SUPERSEDED), outcomes(
                    queueOnLockedRoot(fixture, ascending, 1, MembershipState.ACTIVE, 2, MembershipState.REMOVED)));
            UUID descending = UUID.randomUUID();
            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.STALE), outcomes(
                    queueOnLockedRoot(fixture, descending, 2, MembershipState.REMOVED, 1, MembershipState.ACTIVE)));

            for (UUID group : List.of(ascending, descending)) {
                assertEquals(List.of(new FinancialActor(group, FIRST, MembershipState.REMOVED, 2)),
                        FinanceTestFixture.actors(fixture, group));
            }
        });
    }

    /**
     * Two facts with the same actor and revision queued on the root lock (PRJ-T20): the waiter
     * reads the winner's committed actor, so an identical fact is a duplicate and an opposing one
     * fails as a contradiction. Neither writes, so the winner's row keeps its insert timestamps.
     */
    @Test
    void sameRevisionFactsQueuedOnTheRootLockLeaveTheFirstCommittedFactAuthoritative() {
        runConcurrencyTest(fixture -> {
            UUID identical = UUID.randomUUID();
            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.DUPLICATE), outcomes(
                    queueOnLockedRoot(fixture, identical, 3, MembershipState.ACTIVE, 3, MembershipState.ACTIVE)));

            UUID contradictory = UUID.randomUUID();
            List<Future<MembershipOutcome>> results =
                    queueOnLockedRoot(fixture, contradictory, 3, MembershipState.ACTIVE, 3, MembershipState.REMOVED);
            assertEquals(MembershipOutcome.INSERTED, await(results.get(0)));
            ExecutionException failure = assertThrows(ExecutionException.class, results.get(1)::get);
            ConflictingMembershipFactException conflict =
                    assertInstanceOf(ConflictingMembershipFactException.class, failure.getCause());
            assertEquals(MembershipState.ACTIVE, conflict.storedState());
            assertEquals(MembershipState.REMOVED, conflict.receivedState());

            for (UUID group : List.of(identical, contradictory)) {
                assertEquals(List.of(new FinancialActor(group, FIRST, MembershipState.ACTIVE, 3)),
                        FinanceTestFixture.actors(fixture, group));
                // The waiter's transaction started after the winner's, so any write would have moved updated_at.
                ActorsRecord winner = FinanceTestFixture.actorRow(fixture, group, FIRST);
                assertTrue(winner.getCreatedAt().isEqual(winner.getUpdatedAt()), winner::toString);
                assertEquals(Optional.of(new FinancialGroup(group, FinancialGroupState.PROVISIONING, 0)),
                        FinanceTestFixture.root(fixture, group));
            }
        });
    }

    @Test
    void operationsOfAnotherGroupCommitWhileARootIsLocked() {
        runConcurrencyTest(fixture -> {
            UUID locked = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, locked, FinancialGroupState.OPEN, 0);
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
                 Connection holder = FinanceTestFixture.lockRoot(fixture, locked)) {
                // Bounded waits fail the test if either operation were blocked by the held lock.
                assertEquals(MembershipOutcome.INSERTED,
                        await(workers.submit(() -> apply(fixture, other, FIRST, 1, MembershipState.ACTIVE))));
                assertEquals(ActivationOutcome.ACTIVATED, await(workers.submit(() -> activate(fixture, other))));

                // Committed and visible while the first root is still locked.
                assertEquals(Optional.of(new FinancialGroup(other, FinancialGroupState.OPEN, 0)),
                        FinanceTestFixture.root(fixture, other));
                assertEquals(List.of(new FinancialActor(other, FIRST, MembershipState.ACTIVE, 1)),
                        FinanceTestFixture.actors(fixture, other));
                awaitLockWaiters(fixture, holder, 0, WAIT);
                holder.rollback();
            }
        });
    }

    /**
     * {@code updated_at} is the writing transaction's start time, not its commit or lock order
     * (PRJ-T19). The higher revision's transaction starts first and pauses before its root lock;
     * a later transaction commits a lower revision; the higher revision then supersedes it and
     * remains authoritative although its {@code updated_at} is earlier.
     */
    @Test
    void aHigherRevisionStaysAuthoritativeWhenItsTransactionStartedBeforeALowerRevisionCommitted() {
        runTest(fixture -> {
            UUID group = UUID.randomUUID();
            FinanceTestFixture.seedRoot(fixture, group, FinancialGroupState.OPEN, 7);
            FinanceTestFixture.seedActor(fixture, new FinancialActor(group, FIRST, MembershipState.ACTIVE, 1));
            GroupsRecord root = FinanceTestFixture.rootRow(fixture, group).orElseThrow();
            TransactionStartGate gate = fixture.context().getBean(TransactionStartGate.class);
            OffsetDateTime higherStartedAt;
            ActorsRecord lower;
            MembershipOutcome higherOutcome;
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
                 TransactionStartGate paused = gate.arm()) {
                Future<MembershipOutcome> higher = workers.submit(() ->
                        apply(fixture, group, FIRST, 3, MembershipState.ACTIVE));
                higherStartedAt = await(paused.started());

                // The paused transaction holds no Finance lock, so the later one runs to commit.
                assertEquals(MembershipOutcome.SUPERSEDED,
                        await(workers.submit(() -> apply(fixture, group, FIRST, 2, MembershipState.REMOVED))));
                lower = FinanceTestFixture.actorRow(fixture, group, FIRST);
                assertEquals(new FinancialActor(group, FIRST, MembershipState.REMOVED, 2), toActor(lower));
                assertTrue(higherStartedAt.isBefore(lower.getUpdatedAt()), lower::toString);
                assertEquals(Optional.of(root), FinanceTestFixture.rootRow(fixture, group));
                assertFalse(higher.isDone(), "The higher revision must still be paused");

                paused.close();
                higherOutcome = await(higher);
            }

            assertEquals(MembershipOutcome.SUPERSEDED, higherOutcome);
            ActorsRecord authoritative = FinanceTestFixture.actorRow(fixture, group, FIRST);
            assertEquals(new FinancialActor(group, FIRST, MembershipState.ACTIVE, 3), toActor(authoritative));
            // updated_at decreased although the revision increased.
            assertTrue(higherStartedAt.isEqual(authoritative.getUpdatedAt()), authoritative::toString);
            assertTrue(authoritative.getUpdatedAt().isBefore(lower.getUpdatedAt()), authoritative::toString);
            assertTrue(FinanceTestFixture.SEEDED_AT.isEqual(authoritative.getCreatedAt()), authoritative::toString);
            assertEquals(lower.getCreatedAt(), authoritative.getCreatedAt());
            assertEquals(Optional.of(root), FinanceTestFixture.rootRow(fixture, group));
        }, ProductionEntityScanConfiguration.class, BoundedStatementsConfiguration.class,
                TransactionStartGateConfiguration.class);
    }

    /**
     * Queues two facts for one actor of a seeded root behind a held root lock, in the given
     * order, and returns their completed results in that order. PostgreSQL grants a contended row
     * lock to its waiters in queue order, so the first fact submitted is applied first.
     */
    private static List<Future<MembershipOutcome>> queueOnLockedRoot(IntegrationTestContext fixture, UUID group,
            long firstRevision, MembershipState firstState, long secondRevision, MembershipState secondState)
            throws Exception {
        FinanceTestFixture.seedRoot(fixture, group, FinancialGroupState.PROVISIONING, 0);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
             Connection holder = FinanceTestFixture.lockRoot(fixture, group)) {
            Future<MembershipOutcome> first = workers.submit(() ->
                    apply(fixture, group, FIRST, firstRevision, firstState));
            awaitLockWaiters(fixture, holder, 1, WAIT);
            Future<MembershipOutcome> second = workers.submit(() ->
                    apply(fixture, group, FIRST, secondRevision, secondState));
            awaitLockWaiters(fixture, holder, 2, WAIT);
            holder.commit();
            // Bounded join of both facts, whether they succeed or fail; callers inspect each result.
            workers.shutdown();
            assertTrue(workers.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "Queued facts did not complete");
            return List.of(first, second);
        }
    }

    private static List<MembershipOutcome> outcomes(List<Future<MembershipOutcome>> results) {
        return results.stream().map(PostgresLockWaits::await).toList();
    }

    private static FinancialActor toActor(ActorsRecord row) {
        return new FinancialActor(row.getGroupId(), row.getUserId(),
                row.getDeleted() ? MembershipState.REMOVED : MembershipState.ACTIVE, row.getUpstreamRevision());
    }

    private void runConcurrencyTest(TestCallbacks.Action<IntegrationTestContext> test) {
        runTest(test, ProductionEntityScanConfiguration.class, BoundedStatementsConfiguration.class);
    }

    /**
     * One-shot pause of the next root-lock acquisition after its transaction has started. Closing
     * the armed gate releases the paused operation; the pause itself is bounded by {@link
     * PostgresLockWaits#WAIT}, so a failed test cannot leave the operation waiting indefinitely.
     */
    static final class TransactionStartGate implements AutoCloseable {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CompletableFuture<OffsetDateTime> started = new CompletableFuture<>();
        private final CountDownLatch released = new CountDownLatch(1);

        TransactionStartGate arm() {
            armed.set(true);
            return this;
        }

        /** The paused transaction's start timestamp, as {@code now()} reports it. */
        Future<OffsetDateTime> started() {
            return started;
        }

        @Override
        public void close() {
            released.countDown();
        }

        void pauseIfArmed(Supplier<OffsetDateTime> startTransaction) {
            if (!armed.compareAndSet(true, false)) {
                return;
            }
            try {
                started.complete(startTransaction.get());
            } catch (RuntimeException | Error failure) {
                started.completeExceptionally(failure);
                throw failure;
            }
            try {
                if (!released.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Paused projection operation was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while paused", failure);
            }
        }
    }

    /**
     * Delegates to the production root store but, when the gate is armed, first starts the
     * segment's physical transaction with {@code SELECT now()} and pauses before
     * {@code ensureAndLock}. The service still takes the root lock before any actor access, and
     * no production hook is added: the dispatched operation's own first statement would otherwise
     * acquire the lock immediately after fixing its transaction start.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionStartGateConfiguration {

        @Bean
        TransactionStartGate transactionStartGate() {
            return new TransactionStartGate();
        }

        @Bean
        @Primary
        @Scope("operation")
        FinancialGroupStore gatedGroupStore(@Qualifier("jooqFinancialGroupStore") FinancialGroupStore delegate,
                JooqAccess database, TransactionStartGate gate) {
            return new FinancialGroupStore() {
                @Override
                public FinancialGroup ensureAndLock(UUID groupId) {
                    gate.pauseIfArmed(() -> database.dsl().fetchSingle("select now()").get(0, OffsetDateTime.class));
                    return delegate.ensureAndLock(groupId);
                }

                @Override
                public FinancialGroupLookup lockExisting(UUID groupId) {
                    return delegate.lockExisting(groupId);
                }

                @Override
                public FinancialGroup activate(FinancialGroup locked) {
                    return delegate.activate(locked);
                }
            };
        }
    }
}

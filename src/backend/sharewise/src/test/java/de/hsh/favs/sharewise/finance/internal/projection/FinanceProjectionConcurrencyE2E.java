package de.hsh.favs.sharewise.finance.internal.projection;

import static de.hsh.favs.sharewise.finance.internal.projection.ProjectionDispatch.activate;
import static de.hsh.favs.sharewise.finance.internal.projection.ProjectionDispatch.apply;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.WAIT;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.await;
import static de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits.awaitLockWaiters;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.FinanceTestFixture;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.BoundedStatementsConfiguration;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Concurrent projection operations serialize on their group's root lock only (PRJ-T11 to PRJ-T13).
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
            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.SUPERSEDED),
                    queueOnLockedRoot(fixture, ascending, 1, 2));
            UUID descending = UUID.randomUUID();
            assertEquals(List.of(MembershipOutcome.INSERTED, MembershipOutcome.STALE),
                    queueOnLockedRoot(fixture, descending, 2, 1));

            for (UUID group : List.of(ascending, descending)) {
                assertEquals(List.of(new FinancialActor(group, FIRST, MembershipState.REMOVED, 2)),
                        FinanceTestFixture.actors(fixture, group));
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
     * Queues two facts for one actor of a seeded root behind a held root lock, in the given
     * order, and returns their outcomes in that order. PostgreSQL grants a contended row lock to
     * its waiters in queue order, so the first fact submitted is applied first.
     */
    private static List<MembershipOutcome> queueOnLockedRoot(IntegrationTestContext fixture, UUID group,
            long firstRevision, long secondRevision) throws Exception {
        FinanceTestFixture.seedRoot(fixture, group, FinancialGroupState.PROVISIONING, 0);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
             Connection holder = FinanceTestFixture.lockRoot(fixture, group)) {
            Future<MembershipOutcome> first = workers.submit(() ->
                    apply(fixture, group, FIRST, firstRevision, stateOf(firstRevision)));
            awaitLockWaiters(fixture, holder, 1, WAIT);
            Future<MembershipOutcome> second = workers.submit(() ->
                    apply(fixture, group, FIRST, secondRevision, stateOf(secondRevision)));
            awaitLockWaiters(fixture, holder, 2, WAIT);
            holder.commit();
            return List.of(await(first), await(second));
        }
    }

    private static MembershipState stateOf(long revision) {
        return revision == 1 ? MembershipState.ACTIVE : MembershipState.REMOVED;
    }

    private void runConcurrencyTest(TestCallbacks.Action<IntegrationTestContext> test) {
        runTest(test, ProductionEntityScanConfiguration.class, BoundedStatementsConfiguration.class);
    }
}

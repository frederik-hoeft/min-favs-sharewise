package de.hsh.favs.sharewise.shared.infra.transactions.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exercises the real DI scope and Spring TransactionTemplate against an instrumented physical manager. */
@SpringJUnitConfig(TransactionsIntegrationTests.Fixtures.class)
class TransactionsIntegrationTests {

    @Autowired OperationDispatcher dispatcher;
    @Autowired ServiceScopeFactory scopes;
    @Autowired RecordingTransactionManager physical;
    @Autowired ApplicationContext context;

    @BeforeEach
    void reset() {
        physical.begins.set(0);
        physical.commits.set(0);
        physical.rollbacks.set(0);
        physical.events.clear();
        physical.beginFailure = null;
        physical.commitFailure = null;
        physical.rollbackFailure = null;
        physical.cleanupFailure = null;
    }

    @Test
    void dependencyInjectionSharesOneManagerWithoutStartingATransaction() {
        // Only explicitly imported rollback-mode contexts may see the test substitute.
        assertEquals(0, context.getBeanNamesForType(RollbackTransactions.class).length);
        Transactions first = dispatcher.dispatch(EntryService.class, entry -> {
            assertSame(entry.transactions(), entry.participant().transactions());
            assertEquals(0, physical.begins.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return entry.transactions();
        });
        Transactions second = dispatcher.dispatch(EntryService.class, EntryService::transactions);
        assertNotSame(first, second);
        assertEquals(0, physical.begins.get());

        try (ServiceScope scope = scopes.createScope()) {
            scope.run(provider -> {
                assertSame(provider.getRequiredService(Transactions.class),
                    provider.getRequiredService(EntryService.class).transactions());
                assertEquals(0, physical.begins.get());
                return null;
            });
        }
    }

    @Test
    void explicitOutcomesCommitRollbackAndAbstainWithReturnedResults() {
        Object identity = new Object();
        Object returned = dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            Object committed = transactions.execute(() -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(transactions.isRollbackOnly());
                return TransactionOutcome.commit(identity);
            });
            assertSame(identity, committed);
            assertEquals(1, physical.commits.get());

            String rolledBack = transactions.execute(() -> TransactionOutcome.rollback("denied"));
            assertEquals("denied", rolledBack);
            assertEquals(1, physical.rollbacks.get());

            String abstained = transactions.execute(() -> TransactionOutcome.abstain("not modified"));
            assertEquals("not modified", abstained);
            assertEquals(2, physical.rollbacks.get());

            String nullResult = transactions.execute(() -> TransactionOutcome.commit(null));
            assertNull(nullResult);
            return identity;
        });
        assertSame(identity, returned);
        assertEquals(List.of("begin", "commit", "begin", "rollback", "begin", "rollback", "begin", "commit"), physical.events);
        assertEquals(4, physical.begins.get());
        assertEquals(2, physical.commits.get());
        assertEquals(2, physical.rollbacks.get());
    }

    @Test
    void delegateOutcomeOnlyWhenReturnedAndIgnoreUnusedOutcomes() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            String result = transactions.execute(() -> {
                entry.participant().rollbackOutcome("unused");
                return TransactionOutcome.commit("saved");
            });
            assertEquals("saved", result);

            String delegated = transactions.execute(() -> entry.participant().rollbackOutcome("delegated"));
            assertEquals("delegated", delegated);
            return null;
        });
        assertEquals(List.of("begin", "commit", "begin", "rollback"), physical.events);
    }

    @Test
    void typedBusinessErrorDoesNotSelectRollbackAutomatically() {
        BusinessError error = new BusinessError("already exists");
        BusinessError result = dispatcher.dispatch(EntryService.class, entry ->
            entry.transactions().execute(() -> TransactionOutcome.commit(error)));
        assertSame(error, result);
        assertEquals(List.of("begin", "commit"), physical.events);
    }

    @Test
    void rollbackOnlyAccessorsAffectTheActivePhysicalSegment() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            String refused = transactions.execute(() -> {
                assertFalse(entry.participant().transactions().isRollbackOnly());
                entry.participant().transactions().markRollbackOnly();
                assertTrue(transactions.isRollbackOnly());
                return TransactionOutcome.rollback("refused");
            });
            assertEquals("refused", refused);
            assertThrows(IllegalStateException.class, transactions::markRollbackOnly);
            assertThrows(IllegalStateException.class, transactions::isRollbackOnly);
            return null;
        });
        assertEquals(List.of("begin", "rollback"), physical.events);
    }

    @Test
    void invalidOutcomesAndCallbackFailureDoNotCommit() {
        assertThrows(NullPointerException.class, () -> new TransactionOutcome<>("x", null));
        assertEquals(TransactionDecision.ABSTAIN, TransactionOutcome.abstain("x").decision());

        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            assertThrows(NullPointerException.class, () -> transactions.execute(null));
            assertThrows(NullPointerException.class, () -> transactions.execute(() -> null));
            IllegalArgumentException expected = new IllegalArgumentException("operation failed");
            assertSame(expected, assertThrows(IllegalArgumentException.class,
                () -> transactions.execute(() -> { throw expected; })));
            assertThrows(IllegalStateException.class, () -> transactions.execute(() -> {
                transactions.markRollbackOnly();
                return TransactionOutcome.commit("invalid");
            }));
            return null;
        });
        assertEquals(List.of("begin", "rollback", "begin", "rollback", "begin", "rollback"), physical.events);
    }

    @Test
    void capturedManagerCannotBeUsedWithoutItsActiveScopeOrInsideAnotherScope() {
        Transactions escaped = dispatcher.dispatch(EntryService.class, EntryService::transactions);
        assertThrows(IllegalStateException.class, () -> escaped.execute(() -> TransactionOutcome.commit("x")));
        assertThrows(IllegalStateException.class, escaped::markRollbackOnly);
        assertThrows(IllegalStateException.class, escaped::isRollbackOnly);

        dispatcher.dispatch(EntryService.class, other -> {
            assertThrows(IllegalStateException.class,
                () -> escaped.execute(() -> TransactionOutcome.commit("wrong scope")));
            assertThrows(IllegalStateException.class, escaped::markRollbackOnly);
            assertThrows(IllegalStateException.class, escaped::isRollbackOnly);
            other.transactions().execute(() -> TransactionOutcome.commit("valid"));
            return null;
        });
        assertEquals(List.of("begin", "commit"), physical.events);
    }

    @Test
    void owningThreadIsRequiredForAllManagerOperations() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            FutureTask<Void> wrongThread = new FutureTask<>(() -> {
                assertThrows(IllegalStateException.class,
                    () -> transactions.execute(() -> TransactionOutcome.commit("x")));
                assertThrows(IllegalStateException.class, transactions::markRollbackOnly);
                assertThrows(IllegalStateException.class, transactions::isRollbackOnly);
                return null;
            });
            Thread thread = Thread.ofVirtual().start(wrongThread);
            try {
                wrongThread.get();
                thread.join();
            } catch (Exception failure) {
                throw new AssertionError("Wrong-thread assertions failed", failure);
            }
            transactions.execute(() -> TransactionOutcome.commit("valid"));
            return null;
        });
        assertEquals(List.of("begin", "commit"), physical.events);
    }

    @Test
    void unrelatedSpringTransactionsAndSynchronizationAreRejectedWithoutStartingSegment() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(IllegalStateException.class,
                    () -> transactions.execute(() -> TransactionOutcome.commit("should not run")));
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThrows(IllegalStateException.class,
                    () -> transactions.execute(() -> TransactionOutcome.commit("should not run")));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            assertEquals(0, physical.begins.get());
            transactions.execute(() -> TransactionOutcome.commit("allowed"));
            return null;
        });
        assertEquals(List.of("begin", "commit"), physical.events);
    }

    @Test
    void reentrantExecutionIsRejectedWithoutPoisoningOwningSegment() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            String value = transactions.execute(() -> {
                assertThrows(IllegalStateException.class,
                    () -> transactions.execute(() -> TransactionOutcome.commit("nested")));
                return TransactionOutcome.commit("outer");
            });
            assertEquals("outer", value);
            transactions.execute(() -> TransactionOutcome.rollback("following"));
            return null;
        });
        assertEquals(List.of("begin", "commit", "begin", "rollback"), physical.events);
    }

    @Test
    void rollbackOnlyCommitFailsButDoesNotPoisonNextSegmentAfterConfirmedRollback() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            assertThrows(IllegalStateException.class, () -> transactions.execute(() -> {
                transactions.markRollbackOnly();
                return TransactionOutcome.commit("unsafe");
            }));
            assertEquals("fine", transactions.execute(() -> {
                assertFalse(transactions.isRollbackOnly());
                return TransactionOutcome.commit("fine");
            }));
            return null;
        });
        assertEquals(List.of("begin", "rollback", "begin", "commit"), physical.events);
    }

    @Test
    void callbackAndInvalidOutcomeFailuresMayBeFollowedByNewSegmentAfterConfirmedRollback() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            IllegalArgumentException expected = new IllegalArgumentException("business failed");
            assertSame(expected, assertThrows(IllegalArgumentException.class,
                () -> transactions.execute(() -> { throw expected; })));
            assertThrows(NullPointerException.class, () -> transactions.execute(() -> null));
            assertEquals("recovered", transactions.execute(() -> TransactionOutcome.commit("recovered")));
            return null;
        });
        assertEquals(List.of("begin", "rollback", "begin", "rollback", "begin", "commit"), physical.events);
    }

    @Test
    void failedPhysicalCommitPoisonsManagerAndNeverReturnsSuccess() {
        physical.commitFailure = new IllegalStateException("physical commit failed");
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("not confirmed")));
            assertSame(physical.commitFailure, thrown);
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("must not retry")));
            assertThrows(IllegalStateException.class, transactions::markRollbackOnly);
            return null;
        });
        // Spring may attempt rollback after a failed commit; it cannot make that
        // earlier physical commit failure unambiguous or authorize a new segment.
        assertEquals(List.of("begin", "commit", "rollback"), physical.events);
    }

    @Test
    void failedBeginPoisonsManagerWithoutInvokingUserCallback() {
        physical.beginFailure = new IllegalStateException("physical begin failed");
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            assertSame(physical.beginFailure, assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> {
                    throw new AssertionError("Callback must never run on failed begin");
                })));
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.rollback("cannot reuse")));
            return null;
        });
        assertEquals(List.of("begin"), physical.events);
    }

    @Test
    void failedPhysicalRollbackPoisonsManagerAndPreservesApplicationFailure() {
        physical.rollbackFailure = new IllegalStateException("physical rollback failed");
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            IllegalArgumentException callbackFailure = new IllegalArgumentException("callback failed");
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> { throw callbackFailure; }));
            assertSame(physical.rollbackFailure, thrown);
            assertTrue(List.of(thrown.getSuppressed()).contains(callbackFailure));
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("must not retry")));
            return null;
        });
        assertEquals(List.of("begin", "rollback"), physical.events);
    }

    @Test
    void afterCommitFailureIsReportedDespiteConfirmedPhysicalCommit() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            IllegalStateException expected = new IllegalStateException("afterCommit listener failed");
            assertSame(expected, assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            throw expected;
                        }
                    });
                    return TransactionOutcome.commit("durable");
                })));
            assertEquals(1, physical.commits.get());
            // Commit is confirmed, but Spring invokes cleanup after completion notifications.
            // The failure path cannot establish that resources were fully released.
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        assertEquals(List.of("begin", "commit"), physical.events);
    }


    @Test
    void cleanupFailureAfterConfirmedCommitPoisonsManager() {
        physical.cleanupFailure = new IllegalStateException("cleanup after commit failed");
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            assertSame(physical.cleanupFailure, assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("committed but not cleaned"))));
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        assertEquals(List.of("begin", "commit", "cleanup"), physical.events);
    }

    @Test
    void cleanupFailureAfterCallbackRollbackPoisonsManagerAndRetainsCause() {
        physical.cleanupFailure = new IllegalStateException("cleanup after rollback failed");
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            IllegalArgumentException applicationFailure = new IllegalArgumentException("callback failed");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> { throw applicationFailure; }));
            assertSame(physical.cleanupFailure, failure);
            assertTrue(List.of(failure.getSuppressed()).contains(applicationFailure));
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        assertEquals(List.of("begin", "rollback", "cleanup"), physical.events);
    }

    @Test
    void failedCommitSynchronizationPoisonsManagerDespiteRollbackNotification() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            IllegalStateException expected = new IllegalStateException("beforeCommit rejected");
            assertSame(expected, assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw expected;
                        }
                    });
                    return TransactionOutcome.commit("never committed");
                })));
            // Spring reports a rollback but cannot prove a commit was never attempted.
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("next segment")));
            return null;
        });
        assertEquals(List.of("begin", "rollback"), physical.events);
    }

    @Test
    void capturedSegmentLeasesCannotExecuteOutsideOrAcrossSegments() {
        dispatcher.dispatch(EntryService.class, entry -> {
            Transactions transactions = entry.transactions();
            TransactionSegmentAccess segments = entry.segments();
            assertSame(transactions, segments);
            assertThrows(IllegalStateException.class, segments::captureCurrentSegment);

            FakeSqlCommand first = transactions.execute(() -> {
                FakeSqlCommand command = new FakeSqlCommand(segments.captureCurrentSegment());
                command.execute();
                return TransactionOutcome.commit(command);
            });
            assertEquals(1, first.executions);
            assertThrows(IllegalStateException.class, first::execute);

            transactions.execute(() -> {
                assertThrows(IllegalStateException.class, first::execute);
                FakeSqlCommand second = new FakeSqlCommand(segments.captureCurrentSegment());
                second.execute();
                assertEquals(1, second.executions);
                assertEquals(1, first.executions);
                return TransactionOutcome.rollback(null);
            });
            assertThrows(IllegalStateException.class, first::execute);
            assertEquals(1, first.executions);
            return null;
        });
        assertEquals(List.of("begin", "commit", "begin", "rollback"), physical.events);
    }

    @Test
    void segmentAccessRejectsWrongScopeAndWrongThread() {
        TransactionSegmentAccess escaped = dispatcher.dispatch(EntryService.class, EntryService::segments);
        assertThrows(IllegalStateException.class, escaped::captureCurrentSegment);
        dispatcher.dispatch(EntryService.class, entry -> {
            assertThrows(IllegalStateException.class, escaped::captureCurrentSegment);
            entry.transactions().execute(() -> {
                TransactionSegmentAccess.SegmentLease lease = entry.segments().captureCurrentSegment();
                FutureTask<Void> wrongThread = new FutureTask<>(() -> {
                    assertThrows(IllegalStateException.class, lease::requireCurrent);
                    assertThrows(IllegalStateException.class, entry.segments()::captureCurrentSegment);
                    return null;
                });
                Thread.ofVirtual().start(wrongThread);
                try {
                    wrongThread.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                lease.requireCurrent();
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    @Test
    void segmentLeaseIsInvalidDuringCompletionCallbacks() {
        dispatcher.dispatch(EntryService.class, entry -> {
            entry.transactions().execute(() -> {
                TransactionSegmentAccess.SegmentLease lease = entry.segments().captureCurrentSegment();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        assertThrows(IllegalStateException.class, lease::requireCurrent);
                    }
                });
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    static final class FakeSqlCommand {
        private final TransactionSegmentAccess.SegmentLease lease;
        private int executions;

        FakeSqlCommand(TransactionSegmentAccess.SegmentLease lease) {
            this.lease = lease;
        }

        void execute() {
            lease.requireCurrent();
            executions++;
        }
    }

    @Test
    void independentConcurrentDispatchesHaveIndependentTransactionManagers() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Transactions>> operations = new ArrayList<>();
            // Verify a full transaction per independent scope, not just object allocation.
            for (int index = 0; index < 8; index++) {
                operations.add(() -> dispatcher.dispatch(EntryService.class, entry -> {
                    entry.transactions().execute(() -> TransactionOutcome.commit("ok"));
                    return entry.transactions();
                }));
            }
            List<Future<Transactions>> futures = executor.invokeAll(operations);
            List<Transactions> instances = new ArrayList<>();
            for (Future<Transactions> future : futures) {
                Transactions instance = future.get();
                for (Transactions other : instances) {
                    assertNotSame(other, instance);
                }
                instances.add(instance);
            }
        }
        assertEquals(8, physical.begins.get());
        assertEquals(8, physical.commits.get());
    }

    record BusinessError(String reason) { }

    record EntryService(Transactions transactions, ParticipantService participant,
                        TransactionSegmentAccess segments) { }

    record ParticipantService(Transactions transactions) {
        TransactionOutcome<String> rollbackOutcome(String result) {
            return TransactionOutcome.rollback(result);
        }
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicInteger begins = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException beginFailure;
        volatile RuntimeException commitFailure;
        volatile RuntimeException rollbackFailure;
        volatile RuntimeException cleanupFailure;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
            events.add("begin");
            if (beginFailure != null) {
                throw beginFailure;
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
            events.add("commit");
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
            events.add("rollback");
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            if (cleanupFailure != null) {
                events.add("cleanup");
                throw cleanupFailure;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {
        "de.hsh.favs.sharewise.shared.infra.di.internal",
        "de.hsh.favs.sharewise.shared.infra.transactions.internal"
    })
    static class Fixtures {
        @Bean RecordingTransactionManager transactionManager() { return new RecordingTransactionManager(); }

        @Bean @Scope("operation")
        EntryService entryService(Transactions transactions, ParticipantService participant,
                TransactionSegmentAccess segments) {
            return new EntryService(transactions, participant, segments);
        }

        @Bean @Scope("operation")
        ParticipantService participantService(Transactions transactions) {
            return new ParticipantService(transactions);
        }
    }
}

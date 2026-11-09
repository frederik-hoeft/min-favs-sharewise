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
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exercises the real DI scope and Spring TransactionTemplate against an instrumented physical manager. */
@SpringJUnitConfig(TransactionsIntegrationTests.Fixtures.class)
class TransactionsIntegrationTests {

    @Autowired OperationDispatcher dispatcher;
    @Autowired ServiceScopeFactory scopes;
    @Autowired RecordingTransactionManager physical;

    @BeforeEach
    void reset() {
        physical.begins.set(0);
        physical.commits.set(0);
        physical.rollbacks.set(0);
        physical.events.clear();
    }

    @Test
    void dependencyInjectionSharesOneManagerWithoutStartingATransaction() {
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

    record BusinessError(String reason) { }

    record EntryService(Transactions transactions, ParticipantService participant) { }

    record ParticipantService(Transactions transactions) {
        TransactionOutcome<String> rollbackOutcome(String result) {
            return TransactionOutcome.rollback(result);
        }
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicInteger begins = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final List<String> events = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
            events.add("rollback");
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
        EntryService entryService(Transactions transactions, ParticipantService participant) {
            return new EntryService(transactions, participant);
        }

        @Bean @Scope("operation")
        ParticipantService participantService(Transactions transactions) {
            return new ParticipantService(transactions);
        }
    }
}

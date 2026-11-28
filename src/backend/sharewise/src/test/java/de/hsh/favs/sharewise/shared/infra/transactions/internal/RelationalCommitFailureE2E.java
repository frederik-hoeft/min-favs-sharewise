package de.hsh.favs.sharewise.shared.infra.transactions.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/** PostgreSQL commit-boundary failures using the real JPA transaction manager and connection. */
@org.junit.jupiter.api.Tag("e2e")
class RelationalCommitFailureE2E extends E2ETestBase {

    private OperationDispatcher dispatcher;
    private CommitResponseFaults faults;

    @BeforeEach
    void initialize() {
        // A self-referential FK permits an invalid row to be written and inspected inside
        // the transaction; PostgreSQL checks the constraint only at physical COMMIT.
        startWithSchema("""
            create table groups.tx_commit_failure_probe (
                id integer primary key,
                parent_id integer references groups.tx_commit_failure_probe(id)
                    deferrable initially deferred
            )
            """, ProductionEntityScanConfiguration.class, Fixtures.class);
        dispatcher = context().getBean(OperationDispatcher.class);

        // Register an observation/fault hook on the production manager, not a substitute
        // manager or datasource. JpaTransactionManager.doCommit delegates to JPA/Hibernate,
        // which commits the real PostgreSQL connection.
        JpaTransactionManager manager = assertInstanceOf(JpaTransactionManager.class,
            context().getBean(PlatformTransactionManager.class));
        faults = new CommitResponseFaults();
        List<TransactionExecutionListener> listeners =
            new ArrayList<>(manager.getTransactionExecutionListeners());
        listeners.add(faults);
        manager.setTransactionExecutionListeners(listeners);
    }

    @Test
    void deferredConstraintFailsDuringPhysicalCommitAndPoisonsTheOperationManager() {
        dispatcher.dispatch(Probe.class, probe -> {
            RuntimeException failure = assertThrows(RuntimeException.class,
                () -> probe.transactions().execute(() -> {
                    probe.jooq().dsl().execute(
                        "insert into groups.tx_commit_failure_probe (id, parent_id) values (1, 999)");
                    // The write has already reached PostgreSQL, and the FK is still deferred.
                    assertEquals(1, probe.jooq().dsl().fetchCount(
                        org.jooq.impl.DSL.table("groups.tx_commit_failure_probe")));
                    return TransactionOutcome.commit(null);
                }));
            assertEquals("23503", sqlState(failure));
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(
                () -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        assertFalse(exists(1));
    }

    @Test
    void failedCommitBeforePhysicalIssuanceRollsBackAndPoisonsTheManager() {
        dispatcher.dispatch(Probe.class, probe -> {
            IllegalStateException expected = faults.failBeforePhysicalCommit();
            IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> probe.transactions().execute(() -> {
                    probe.jooq().dsl().execute(
                        "insert into groups.tx_commit_failure_probe (id) values (2)");
                    return TransactionOutcome.commit(null);
                }));
            assertSame(expected, actual);
            assertEquals(1, faults.injectedFailures());
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(
                () -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        assertFalse(exists(2));
    }

    @Test
    void failedCommitAcknowledgementCanFollowAnActualDurableCommit() {
        dispatcher.dispatch(Probe.class, probe -> {
            IllegalStateException expected = faults.failAfterPhysicalCommit();
            IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> probe.transactions().execute(() -> {
                    probe.jooq().dsl().execute(
                        "insert into groups.tx_commit_failure_probe (id) values (3)");
                    return TransactionOutcome.commit(null);
                }));
            assertSame(expected, actual);
            assertEquals(1, faults.injectedFailures());
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(
                () -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        // The application observed an exception, but PostgreSQL already committed the row.
        assertTrue(exists(3));
    }

    private boolean exists(int id) {
        try (Connection connection = fixture().connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(
                 "select count(*) from groups.tx_commit_failure_probe where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1) == 1;
            }
        } catch (SQLException failure) {
            throw new AssertionError("Unable to inspect independent PostgreSQL commit state", failure);
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("Expected a PostgreSQL constraint failure", failure);
    }

    record Probe(Transactions transactions, JooqAccess jooq) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Scope("operation")
        Probe probe(Transactions transactions, JooqAccess jooq) {
            return new Probe(transactions, jooq);
        }
    }

    /**
     * One-shot faults attached only to this test's real JpaTransactionManager. A fault fires only
     * on the thread that armed it, so background transactions of the same context (for example
     * the event publication staleness monitor) can neither consume nor trigger it.
     */
    private static final class CommitResponseFaults implements TransactionExecutionListener {
        private enum InjectionPoint { NONE, BEFORE_PHYSICAL_COMMIT, AFTER_PHYSICAL_COMMIT }

        private volatile InjectionPoint point = InjectionPoint.NONE;
        private volatile Thread armedThread;
        private IllegalStateException failure;
        private int injectedFailures;

        IllegalStateException failBeforePhysicalCommit() {
            return arm(InjectionPoint.BEFORE_PHYSICAL_COMMIT);
        }

        IllegalStateException failAfterPhysicalCommit() {
            return arm(InjectionPoint.AFTER_PHYSICAL_COMMIT);
        }

        int injectedFailures() {
            return injectedFailures;
        }

        private IllegalStateException arm(InjectionPoint requested) {
            if (point != InjectionPoint.NONE) {
                throw new IllegalStateException("Commit fault already armed");
            }
            point = requested;
            armedThread = Thread.currentThread();
            failure = new IllegalStateException("Simulated lost commit result: " + requested);
            return failure;
        }

        @Override
        public void beforeCommit(TransactionExecution transaction) {
            if (transaction.isNewTransaction() && point == InjectionPoint.BEFORE_PHYSICAL_COMMIT
                    && Thread.currentThread() == armedThread) {
                inject();
            }
        }

        @Override
        public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
            // Spring calls this only after its real doCommit path. In particular, never
            // simulate a durable commit if the real database already reported a failure.
            if (transaction.isNewTransaction() && commitFailure == null
                    && point == InjectionPoint.AFTER_PHYSICAL_COMMIT && Thread.currentThread() == armedThread) {
                inject();
            }
        }

        private void inject() {
            IllegalStateException injected = failure;
            point = InjectionPoint.NONE;
            armedThread = null;
            failure = null;
            injectedFailures++;
            throw injected;
        }
    }
}

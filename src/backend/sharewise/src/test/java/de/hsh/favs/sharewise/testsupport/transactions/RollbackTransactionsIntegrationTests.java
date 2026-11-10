package de.hsh.favs.sharewise.testsupport.transactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestScope;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactionTestConfiguration;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import de.hsh.favs.sharewise.testsupport.transactions.TransactionObservation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Query;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Preliminary H2 evidence for logical leases and wiring; PostgreSQL acceptance is Phase D. */
@SpringJUnitConfig(RollbackTransactionsIntegrationTests.Fixtures.class)
class RollbackTransactionsIntegrationTests {

    @Autowired ServiceScopeFactory scopes;
    @Autowired PlatformTransactionManager physicalManager;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearRows() {
        jdbc.update("delete from rollback_tx_probe");
    }

    private <T> T inPhysicalTestTransaction(Function<OperationTestScope, T> action) {
        TransactionTemplate transaction = new TransactionTemplate(physicalManager);
        return transaction.execute(status -> {
            try {
                // The physical transaction is established before any scoped bean is resolved.
                return new OperationTestHarness(scopes).inOperationScope(action);
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void rollbackConfigurationProvidesOneOperationScopedManagerThroughBothInterfaces() {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
            Probe probe = scope.resolve(Probe.class);
            assertSame(manager, scope.resolve(Transactions.class));
            assertSame(manager, scope.resolve(TransactionSegmentAccess.class));
            assertSame(manager, probe.transactions());
            assertSame(manager, probe.segments());
            assertThrows(IllegalStateException.class, manager::captureCurrentSegment);
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            return null;
        });
        RollbackTransactions first = inPhysicalTestTransaction(scope -> scope.resolve(RollbackTransactions.class));
        RollbackTransactions second = inPhysicalTestTransaction(scope -> scope.resolve(RollbackTransactions.class));
        assertNotSame(first, second);
        assertThrows(IllegalStateException.class, first::observation);
    }

    @Test
    void logicalExecutionRequiresAnAlreadyActivePhysicalTransaction() {
        new OperationTestHarness(scopes).inOperationScope(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("not started")));
            assertThrows(IllegalStateException.class, transactions::observation);
            return null;
        });
    }

    @Test
    void ambientSynchronizationWithoutAPhysicalTransactionIsNotEnough() {
        new OperationTestHarness(scopes).inOperationScope(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertThrows(IllegalStateException.class,
                    () -> transactions.execute(() -> TransactionOutcome.abstain(null)));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            return null;
        });
    }

    @Test
    void logicalCompletionDoesNotImplicitlyFlushJpaChanges() {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            transactions.execute(() -> {
                scope.resolve(JpaAccess.class).entityManager().persist(new ProbeRow(601, "unflushed"));
                return TransactionOutcome.rollback(null);
            });
            assertEquals(TransactionDecision.ROLLBACK, transactions.observation().requestedDecision());
            assertEquals(0, jdbc.queryForObject(
                "select count(*) from rollback_tx_probe where id = 601", Integer.class));
            return null;
        });
    }

    @Test
    void allThreeDecisionsRecordIntentWhilePhysicalWritesRemainPending() {
        int id = 100;
        for (TransactionDecision decision : TransactionDecision.values()) {
            int rowId = id++;
            String expected = "result-" + decision;
            inPhysicalTestTransaction(scope -> {
                RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
                assertEquals(expected, transactions.execute(() -> {
                    scope.resolve(JooqAccess.class).dsl().execute(
                        "insert into rollback_tx_probe (id, label) values (?, ?)", rowId, expected);
                    return new TransactionOutcome<>(expected, decision);
                }));
                TransactionObservation observed = transactions.observation();
                assertEquals(decision, observed.requestedDecision());
                assertEquals(expected, observed.result());
                assertFalse(observed.rollbackOnly());
                assertNull(observed.failure());
                assertTrue(observed.completed());
                // This is an ordinary test-side JDBC read within the still-open physical
                // transaction, not a new application segment or the Phase D inspector.
                assertEquals(expected, jdbc.queryForObject(
                    "select label from rollback_tx_probe where id = ?", String.class, rowId));
                return null;
            });
            assertEquals(0, jdbc.queryForObject(
                "select count(*) from rollback_tx_probe where id = ?", Integer.class, rowId));
        }
    }

    @Test
    void typedBusinessErrorDoesNotImplicitlyPoisonCommit() {
        record NotFound(int id) { }
        NotFound error = new NotFound(401);
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            assertSame(error, transactions.execute(() -> TransactionOutcome.commit(error)));
            assertSame(error, transactions.observation().result());
            assertEquals(TransactionDecision.COMMIT, transactions.observation().requestedDecision());
            assertFalse(transactions.observation().rollbackOnly());
            return null;
        });
    }

    @Test
    void poisonedCommitFailsAndRetainsTheRequestedDecisionAndFailure() {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                transactions.execute(() -> {
                    transactions.markRollbackOnly();
                    assertTrue(transactions.isRollbackOnly());
                    return TransactionOutcome.commit("unsafe");
                }));
            TransactionObservation observed = transactions.observation();
            assertEquals(TransactionDecision.COMMIT, observed.requestedDecision());
            assertEquals("unsafe", observed.result());
            assertTrue(observed.rollbackOnly());
            assertSame(failure, observed.failure());
            assertThrows(IllegalStateException.class, transactions::markRollbackOnly);
            assertThrows(IllegalStateException.class, transactions::isRollbackOnly);
            return null;
        });
    }

    @Test
    void rollbackOnlyRemainsStickyButAllowsRollbackOrAbstain() {
        for (TransactionDecision decision : new TransactionDecision[]{
                TransactionDecision.ROLLBACK, TransactionDecision.ABSTAIN}) {
            inPhysicalTestTransaction(scope -> {
                RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
                transactions.execute(() -> {
                    transactions.markRollbackOnly();
                    transactions.markRollbackOnly();
                    return new TransactionOutcome<>(null, decision);
                });
                assertTrue(transactions.observation().rollbackOnly());
                assertEquals(decision, transactions.observation().requestedDecision());
                assertNull(transactions.observation().failure());
                return null;
            });
        }
    }

    @Test
    void nestedAndSubsequentLogicalSegmentsAreRejected() {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            assertThrows(IllegalStateException.class, () -> transactions.execute(() -> {
                assertThrows(IllegalStateException.class, () -> transactions.execute(
                    () -> TransactionOutcome.commit("nested")));
                return TransactionOutcome.commit("outer cannot commit after nested attempt");
            }));
            assertTrue(transactions.observation().rollbackOnly());
            assertThrows(IllegalStateException.class, () -> transactions.execute(
                () -> TransactionOutcome.rollback("second")));
            return null;
        });
    }

    @Test
    void callbackExceptionsAndNullOutcomesAreRecordedWithoutAllowingReuse() {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            IllegalArgumentException expected = new IllegalArgumentException("callback failed");
            assertSame(expected, assertThrows(IllegalArgumentException.class,
                () -> transactions.execute(() -> { throw expected; })));
            TransactionObservation observed = transactions.observation();
            assertNull(observed.requestedDecision());
            assertNull(observed.result());
            assertSame(expected, observed.failure());
            assertTrue(observed.completed());
            assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> TransactionOutcome.commit("retry")));
            return null;
        });
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            NullPointerException failure = assertThrows(NullPointerException.class,
                () -> transactions.execute(() -> null));
            assertSame(failure, transactions.observation().failure());
            assertNull(transactions.observation().requestedDecision());
            return null;
        });
    }

    @Test
    void existingJpaAndJooqFacadesAndDeferredQueriesExpireWithLogicalCallback() {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            Probe probe = scope.resolve(Probe.class);
            AtomicReference<DSLContext> dsl = new AtomicReference<>();
            AtomicReference<org.jooq.Query> deferredJooq = new AtomicReference<>();
            AtomicReference<EntityManager> entityManager = new AtomicReference<>();
            AtomicReference<Query> deferredJpa = new AtomicReference<>();
            TransactionSegmentAccess.SegmentLease lease = transactions.execute(() -> {
                DSLContext db = probe.jooq().dsl();
                EntityManager em = probe.jpa().entityManager();
                dsl.set(db);
                entityManager.set(em);
                deferredJooq.set(db.query("select count(*) from rollback_tx_probe"));
                deferredJpa.set(em.createQuery("select count(p) from RollbackProbeRow p"));
                db.execute("insert into rollback_tx_probe (id, label) values (501, 'pending')");
                em.persist(new ProbeRow(502, "from JPA"));
                em.flush();
                TransactionSegmentAccess.SegmentLease current = probe.segments().captureCurrentSegment();
                current.requireCurrent();
                return TransactionOutcome.commit(current);
            });
            assertThrows(IllegalStateException.class, lease::requireCurrent);
            assertThrows(IllegalStateException.class, () -> probe.jooq().dsl());
            assertThrows(IllegalStateException.class, () -> probe.jpa().entityManager());
            assertThrows(IllegalStateException.class, () -> dsl.get().fetch("select 1"));
            assertGuardRejected(() -> deferredJooq.get().execute());
            assertGuardRejected(() -> entityManager.get().find(ProbeRow.class, 502));
            assertGuardRejected(() -> deferredJpa.get().getResultList());
            assertEquals(2, jdbc.queryForObject("select count(*) from rollback_tx_probe", Integer.class));
            return null;
        });
        assertEquals(0, jdbc.queryForObject("select count(*) from rollback_tx_probe", Integer.class));
    }

    @Test
    void originalSpringSynchronizationMustBePresentDuringLeaseUse() {
        for (int propagation : new int[]{TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED}) {
            inPhysicalTestTransaction(scope -> {
                Probe probe = scope.resolve(Probe.class);
                RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
                transactions.execute(() -> {
                    DSLContext db = probe.jooq().dsl();
                    EntityManager em = probe.jpa().entityManager();
                    TransactionSegmentAccess.SegmentLease lease = probe.segments().captureCurrentSegment();
                    assertEquals(0, db.fetchCount(DSL.table("rollback_tx_probe")));
                    TransactionTemplate nested = new TransactionTemplate(physicalManager);
                    nested.setPropagationBehavior(propagation);
                    nested.execute(status -> {
                        assertThrows(IllegalStateException.class, lease::requireCurrent);
                        assertThrows(IllegalStateException.class, transactions::markRollbackOnly);
                        assertThrows(IllegalStateException.class, transactions::isRollbackOnly);
                        assertThrows(IllegalStateException.class, () -> probe.jooq().dsl());
                        assertThrows(IllegalStateException.class, () -> probe.jpa().entityManager());
                        assertGuardRejected(() -> db.fetch("select 1"));
                        assertGuardRejected(() -> em.createQuery("select count(p) from RollbackProbeRow p").getResultList());
                        return null;
                    });
                    lease.requireCurrent();
                    assertEquals(0, db.fetchCount(DSL.table("rollback_tx_probe")));
                    return TransactionOutcome.rollback(null);
                });
                return null;
            });
        }
    }

    @Test
    void owningThreadAndOperationScopeCannotBeEscaped() throws Exception {
        inPhysicalTestTransaction(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            transactions.execute(() -> {
                TransactionSegmentAccess.SegmentLease lease = transactions.captureCurrentSegment();
                FutureTask<IllegalStateException> otherThread = new FutureTask<>(() ->
                    assertThrows(IllegalStateException.class, lease::requireCurrent));
                Thread worker = new Thread(otherThread);
                worker.start();
                try {
                    assertNotNull(otherThread.get());
                    worker.join();
                } catch (Exception failure) {
                    throw new AssertionError("Other-thread check did not finish", failure);
                }
                new OperationTestHarness(scopes).inOperationScope(inner -> {
                    assertNotSame(transactions, inner.resolve(RollbackTransactions.class));
                    assertThrows(IllegalStateException.class, lease::requireCurrent);
                    return null;
                });
                lease.requireCurrent();
                return TransactionOutcome.abstain(null);
            });
            return null;
        });
    }

    /** Reject a facade only if its segment guard fired, not for unrelated SQL/JPA errors. */
    private static void assertGuardRejected(Runnable action) {
        RuntimeException thrown = assertThrows(RuntimeException.class, action::run);
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof IllegalStateException,
            "Expected a segment-lifetime rejection, got: " + thrown);
    }

    record Probe(Transactions transactions, TransactionSegmentAccess segments, JooqAccess jooq, JpaAccess jpa) { }

    @Entity(name = "RollbackProbeRow")
    @Table(name = "rollback_tx_probe")
    public static class ProbeRow {
        @Id
        private Integer id;
        @Column(nullable = false)
        private String label;

        protected ProbeRow() { }

        ProbeRow(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {
        "de.hsh.favs.sharewise.shared.infra.di.internal",
        "de.hsh.favs.sharewise.shared.infra.transactions.internal",
        "de.hsh.favs.sharewise.shared.infra.persistence.jooq.internal",
        "de.hsh.favs.sharewise.shared.infra.persistence.jpa.internal"
    })
    @Import(RollbackTransactionTestConfiguration.class)
    static class Fixtures {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:p13c_logical;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(ProbeRow.class.getPackageName());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        DSLContext dslContext(DataSource dataSource) {
            return DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.H2);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean @Scope("operation")
        Probe probe(Transactions transactions, TransactionSegmentAccess segments, JooqAccess jooq, JpaAccess jpa) {
            return new Probe(transactions, segments, jooq, jpa);
        }
    }
}

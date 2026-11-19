package de.hsh.favs.sharewise.tests.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.p13crollback.RollbackProbeRow;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.transactions.PendingStateInspector;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestRunner;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import de.hsh.favs.sharewise.testsupport.transactions.TransactionObservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL-only acceptance for one logical segment inside a physically rolled-back transaction. */
@Tag("integration")
class PostgresRollbackOperationIT extends IntegrationTestBase {

    @Test
    void commitRollbackAndAbstainRemainPendingUntilHarnessRollback() throws SQLException {
        IntegrationTestContext fixture = fixture();
        for (TransactionDecision decision : TransactionDecision.values()) {
            IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
            String result = harness.inRollbackOperation(scope -> {
                RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
                assertSame(manager, scope.resolve(Transactions.class));
                assertSame(manager, scope.resolve(TransactionSegmentAccess.class));
                assertEquals("operation-result", manager.execute(() -> {
                    scope.resolve(JooqAccess.class).dsl().execute(
                        "insert into groups.p13c_rollback_probe (id,label) values (2,'pending')");
                    return new TransactionOutcome<>("operation-result", decision);
                }));
                TransactionObservation observed = scope.observation();
                assertEquals(decision, observed.requestedDecision());
                assertEquals("operation-result", observed.result());
                assertNull(observed.failure());
                assertFalse(observed.rollbackOnly());
                assertTrue(observed.completed());
                assertEquals("pending", scope.inspect(inspector -> inspector.queryForObject(
                    "select label from groups.p13c_rollback_probe where id = ?", String.class, 2)));
                assertEquals(List.of("baseline", "pending"), scope.inspect(inspector -> inspector.query(
                    "select label from groups.p13c_rollback_probe order by id",
                    (row, rowNumber) -> row.getString(1))));
                Integer independentlyCommitted = assertDoesNotThrow(() -> independentlyVisible(fixture, 2));
                assertEquals(0, independentlyCommitted.intValue());
                return "operation-result";
            });
            assertEquals("operation-result", result);
            assertEquals(0, independentlyVisible(fixture, 2));
            assertEquals(1, independentlyVisible(fixture, 1));
        }
    }

    @Test
    void inspectorUsesOriginalPostgresSessionAndExpiresAfterCallback() {
        IntegrationTestContext fixture = fixture();
        AtomicReference<PendingStateInspector> retainedInspector = new AtomicReference<>();
        AtomicReference<RollbackTransactions> retainedManager = new AtomicReference<>();
        runTransactionTest((manager, scope) -> {
            retainedManager.set(manager);
            Integer backendPid = manager.execute(() -> {
                Integer pid = scope.resolve(JooqAccess.class).dsl()
                    .fetchOne("select pg_backend_pid()").get(0, Integer.class);
                return TransactionOutcome.commit(pid);
            });
            Integer inspectedPid = scope.inspect(inspector -> {
                retainedInspector.set(inspector);
                return inspector.queryForObject("select pg_backend_pid()", Integer.class);
            });
            assertEquals(backendPid, inspectedPid);
            assertThrows(IllegalStateException.class,
                () -> retainedInspector.get().queryForObject("select 1", Integer.class));
        });
        assertThrows(IllegalStateException.class, retainedManager.get()::observation);
        assertThrows(IllegalStateException.class,
            () -> retainedInspector.get().queryForObject("select 1", Integer.class));
    }

    @Test
    void pendingJpaChangesAreNotFlushedByInspector() throws SQLException {
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        harness.inRollbackOperation(scope -> {
            scope.resolve(Transactions.class).execute(() -> {
                scope.resolve(JpaAccess.class).entityManager().persist(new RollbackProbeRow(3, "unflushed"));
                return TransactionOutcome.commit(null);
            });
            Integer unflushed = scope.inspect(inspector -> inspector.queryForObject(
                "select count(*) from groups.p13c_rollback_probe where id = 3", Integer.class));
            assertEquals(0, unflushed.intValue());
            return null;
        });
        assertEquals(0, independentlyVisible(fixture, 3));
        harness.inRollbackOperation(scope -> {
            scope.resolve(Transactions.class).execute(() -> {
                EntityManager manager = scope.resolve(JpaAccess.class).entityManager();
                manager.persist(new RollbackProbeRow(4, "explicitly-flushed"));
                manager.flush();
                return TransactionOutcome.commit(null);
            });
            assertEquals("explicitly-flushed", scope.inspect(inspector -> inspector.queryForObject(
                "select label from groups.p13c_rollback_probe where id = 4", String.class)));
            return null;
        });
        assertEquals(0, independentlyVisible(fixture, 4));
    }

    @Test
    void poisonedCommitAndTypedBusinessResultAreDistinguished() throws SQLException {
        record NotFound(int id) { }
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        harness.inRollbackOperation(scope -> {
            RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                manager.execute(() -> {
                    manager.markRollbackOnly();
                    return TransactionOutcome.commit("invalid");
                }));
            assertEquals(TransactionDecision.COMMIT, scope.observation().requestedDecision());
            assertTrue(scope.observation().rollbackOnly());
            assertSame(failure, scope.observation().failure());
            return null;
        });
        harness.inRollbackOperation(scope -> {
            NotFound notFound = new NotFound(987);
            assertSame(notFound, scope.resolve(Transactions.class).execute(
                () -> TransactionOutcome.commit(notFound)));
            assertFalse(scope.observation().rollbackOnly());
            assertEquals(TransactionDecision.COMMIT, scope.observation().requestedDecision());
            return null;
        });
        assertEquals(1, independentlyVisible(fixture, 1));
    }

    @Test
    void callbackExceptionsAndExceptionalAssertionsAlwaysRollback() throws SQLException {
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        IllegalArgumentException expected = new IllegalArgumentException("application callback");
        harness.inRollbackOperation(scope -> {
            RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
            assertSame(expected, assertThrows(IllegalArgumentException.class, () ->
                manager.execute(() -> {
                    insert(scope, 5, "callback");
                    throw expected;
                })));
            assertSame(expected, scope.observation().failure());
            assertNull(scope.observation().requestedDecision());
            Integer pending = scope.inspect(inspector -> inspector.queryForObject(
                "select count(*) from groups.p13c_rollback_probe where id=5", Integer.class));
            assertEquals(1, pending.intValue());
            return null;
        });
        assertEquals(0, independentlyVisible(fixture, 5));
        AssertionError failedAssertion = new AssertionError("intentional assertion failure");
        assertSame(failedAssertion, assertThrows(AssertionError.class,
            () -> harness.inRollbackOperation(scope -> {
                scope.resolve(Transactions.class).execute(() -> {
                    insert(scope, 6, "assertion failure");
                    return TransactionOutcome.commit(null);
                });
                throw failedAssertion;
            })));
        assertEquals(0, independentlyVisible(fixture, 6));
    }

    @Test
    void postgresConstraintErrorAbortsTransactionButTeardownRestoresDatabase() throws SQLException {
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        harness.inRollbackOperation(scope -> {
            RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
            DuplicateKeyException failure = assertThrows(
                DuplicateKeyException.class, () -> manager.execute(() -> {
                    insert(scope, 7, "first");
                    insert(scope, 7, "duplicate key");
                    return TransactionOutcome.commit(null);
                }));
            assertSame(failure, scope.observation().failure());
            assertNull(scope.observation().requestedDecision());
            // PostgreSQL rejected the statement and aborted the transaction: no further
            // SQL inspection is valid; the outer harness must still roll everything back.
            return null;
        });
        assertEquals(0, independentlyVisible(fixture, 7));
        assertEquals(1, independentlyVisible(fixture, 1));
    }

    @Test
    void inspectionNeedsACompletedSegmentAndTheOriginalActiveTransaction() throws SQLException {
        IntegrationTestContext fixture = fixture();
        PlatformTransactionManager physical = fixture.context().getBean(PlatformTransactionManager.class);
        new IntegrationTestRunner(fixture).inRollbackOperation(scope -> {
            assertThrows(IllegalStateException.class, () -> scope.inspect(
                inspector -> inspector.queryForObject("select 1", Integer.class)));
            scope.resolve(Transactions.class).execute(() -> TransactionOutcome.commit(null));
            TransactionTemplate suspended = new TransactionTemplate(physical);
            suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            suspended.execute(status -> {
                assertThrows(IllegalStateException.class, () -> scope.inspect(
                    inspector -> inspector.queryForObject("select 1", Integer.class)));
                return null;
            });
            assertEquals(Integer.valueOf(1), scope.inspect(
                inspector -> inspector.queryForObject("select 1", Integer.class)));
            return null;
        });
    }

    @Test
    void ambientPhysicalTransactionCannotBeReusedAsTheHarnessTransaction() throws SQLException {
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        TransactionTemplate outer = new TransactionTemplate(
            fixture.context().getBean(PlatformTransactionManager.class));
        outer.execute(status -> {
            assertThrows(IllegalStateException.class, () -> harness.inRollbackOperation(
                scope -> null));
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void staleJooqAndJpaFacadesCannotBeReusedByInspection() throws SQLException {
        IntegrationTestContext fixture = fixture();
        new IntegrationTestRunner(fixture).inRollbackOperation(scope -> {
            AtomicReference<DSLContext> retainedDsl = new AtomicReference<>();
            AtomicReference<org.jooq.Query> retainedQuery = new AtomicReference<>();
            AtomicReference<EntityManager> retainedJpa = new AtomicReference<>();
            AtomicReference<Query> retainedJpaQuery = new AtomicReference<>();
            scope.resolve(Transactions.class).execute(() -> {
                DSLContext dsl = scope.resolve(JooqAccess.class).dsl();
                EntityManager jpa = scope.resolve(JpaAccess.class).entityManager();
                retainedDsl.set(dsl);
                retainedQuery.set(dsl.query("select 1"));
                retainedJpa.set(jpa);
                retainedJpaQuery.set(jpa.createQuery("select count(r) from RollbackProbeRow r"));
                dsl.execute("insert into groups.p13c_rollback_probe (id,label) values (8,'pending')");
                return TransactionOutcome.commit(null);
            });
            assertThrows(IllegalStateException.class, () -> scope.resolve(JooqAccess.class).dsl());
            assertThrows(IllegalStateException.class, () -> scope.resolve(JpaAccess.class).entityManager());
            assertGuardRejected(() -> retainedDsl.get().fetch("select 1"));
            assertGuardRejected(() -> retainedQuery.get().execute());
            assertGuardRejected(() -> retainedJpa.get().find(RollbackProbeRow.class, 8));
            assertGuardRejected(() -> retainedJpaQuery.get().getResultList());
            Integer pending = scope.inspect(inspector -> inspector.queryForObject(
                "select count(*) from groups.p13c_rollback_probe where id=8", Integer.class));
            assertEquals(1, pending.intValue());
            return null;
        });
        assertEquals(0, independentlyVisible(fixture, 8));
    }

    @Test
    void nestedAndRepeatedLogicalSegmentsAreRejected() throws SQLException {
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        harness.inRollbackOperation(scope -> {
            RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
            assertThrows(IllegalStateException.class, () -> manager.execute(() -> {
                assertThrows(IllegalStateException.class, () -> manager.execute(
                    () -> TransactionOutcome.commit("nested")));
                return TransactionOutcome.commit("cannot commit");
            }));
            assertTrue(scope.observation().rollbackOnly());
            assertThrows(IllegalStateException.class,
                () -> manager.execute(() -> TransactionOutcome.rollback(null)));
            return null;
        });
        assertEquals(1, independentlyVisible(fixture, 1));
    }

    @Test
    void firstLogicalSegmentRejectsRequiresNewBeforeAnyCallbackOrCommit() throws SQLException {
        IntegrationTestContext fixture = fixture();
        PlatformTransactionManager physical = fixture.context().getBean(PlatformTransactionManager.class);
        JdbcTemplate jdbc = fixture.context().getBean(JdbcTemplate.class);
        AtomicBoolean callbackEntered = new AtomicBoolean();
        int rowId = 33;

        new IntegrationTestRunner(fixture).inRollbackOperation(scope -> {
            RollbackTransactions transactions = scope.resolve(RollbackTransactions.class);
            TransactionTemplate independentlyCommitting = new TransactionTemplate(physical);
            independentlyCommitting.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independentlyCommitting.execute(status -> {
                IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> transactions.execute(() -> {
                        callbackEntered.set(true);
                        // Direct JDBC deliberately bypasses IntegrationTestScope's own guard:
                        // this must be rejected by RollbackTransactions before user code runs.
                        jdbc.update("insert into groups.p13c_rollback_probe (id,label) values (?,?)",
                            rowId, "escaped");
                        return TransactionOutcome.commit(null);
                    }));
                assertTrue(rejected.getMessage().contains("harness-owned"));
                return null;
            });
            assertFalse(callbackEntered.get(), "The first segment must not enter an unrelated transaction");
            assertThrows(IllegalStateException.class, transactions::observation);
            return null;
        });
        assertEquals(0, independentlyVisible(fixture, rowId));
    }

    @Test
    void suspensionRejectsBothDatabaseAdaptersUntilOriginalTransactionResumes() throws SQLException {
        IntegrationTestContext fixture = fixture();
        PlatformTransactionManager physical = fixture.context().getBean(PlatformTransactionManager.class);
        for (int propagation : new int[]{TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED}) {
            new IntegrationTestRunner(fixture).inRollbackOperation(scope -> {
                RollbackTransactions manager = scope.resolve(RollbackTransactions.class);
                manager.execute(() -> {
                    JooqAccess jooq = scope.resolve(JooqAccess.class);
                    JpaAccess jpa = scope.resolve(JpaAccess.class);
                    DSLContext dsl = jooq.dsl();
                    EntityManager em = jpa.entityManager();
                    TransactionSegmentAccess.SegmentLease lease = manager.captureCurrentSegment();
                    TransactionTemplate suspended = new TransactionTemplate(physical);
                    suspended.setPropagationBehavior(propagation);
                    suspended.execute(status -> {
                        assertThrows(IllegalStateException.class, lease::requireCurrent);
                        assertThrows(IllegalStateException.class, () -> jooq.dsl());
                        assertThrows(IllegalStateException.class, () -> jpa.entityManager());
                        assertGuardRejected(() -> dsl.fetch("select 1"));
                        assertGuardRejected(
                            () -> em.createQuery("select count(r) from RollbackProbeRow r").getResultList());
                        return null;
                    });
                    lease.requireCurrent();
                    assertEquals(1, dsl.fetchCount(org.jooq.impl.DSL.table(
                        "groups.p13c_rollback_probe")));
                    return TransactionOutcome.rollback(null);
                });
                return null;
            });
        }
    }

    @Test
    void concurrentRollbackOperationsNeverPublishPendingRows() throws Exception {
        IntegrationTestContext fixture = fixture();
        IntegrationTestRunner harness = new IntegrationTestRunner(fixture);
        CountDownLatch pendingBoth = new CountDownLatch(2);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> first = workers.submit(() -> concurrentOperation(fixture, harness, pendingBoth, 20));
            Future<Void> second = workers.submit(() -> concurrentOperation(fixture, harness, pendingBoth, 21));
            first.get(40, TimeUnit.SECONDS);
            second.get(40, TimeUnit.SECONDS);
        }
        assertEquals(0, independentlyVisible(fixture, 20));
        assertEquals(0, independentlyVisible(fixture, 21));
    }

    private static Void concurrentOperation(IntegrationTestContext fixture,
            IntegrationTestRunner harness, CountDownLatch pendingBoth, int id) throws Exception {
        harness.inRollbackOperation(scope -> {
            scope.resolve(Transactions.class).execute(() -> {
                insert(scope, id, "concurrent");
                return TransactionOutcome.commit(null);
            });
            pendingBoth.countDown();
            try {
                assertTrue(pendingBoth.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            Integer pending = scope.inspect(inspector -> inspector.queryForObject(
                "select count(*) from groups.p13c_rollback_probe where id = ?", Integer.class, id));
            assertEquals(1, pending.intValue());
            try {
                assertEquals(0, independentlyVisible(fixture, id));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return null;
        });
        return null;
    }

    private static void assertGuardRejected(Runnable action) {
        RuntimeException failure = assertThrows(RuntimeException.class, action::run);
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof IllegalStateException,
            "Expected a segment lifetime rejection, got: " + failure);
    }

    private static void insert(de.hsh.favs.sharewise.testsupport.integration.IntegrationTestScope scope,
            int id, String label) {
        scope.resolve(JooqAccess.class).dsl().execute(
            "insert into groups.p13c_rollback_probe (id,label) values (?,?)", id, label);
    }

    private static int independentlyVisible(IntegrationTestContext fixture, int id) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(
                 "select count(*) from groups.p13c_rollback_probe where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }
}

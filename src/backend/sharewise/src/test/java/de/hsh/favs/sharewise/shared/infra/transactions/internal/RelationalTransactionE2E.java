package de.hsh.favs.sharewise.shared.infra.transactions.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresE2ETestCase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real-commit mixed jOOQ/JPA transactions and failure handling against isolated PostgreSQL. */
@org.junit.jupiter.api.Tag("e2e")
class RelationalTransactionE2E extends PostgresE2ETestCase {

    OperationDispatcher dispatcher;
    DataSource dataSource;
    JdbcTemplate jdbc;
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetRows() {
        startWithSchema("create table groups.tx_joint_probe (id integer primary key, label varchar(255) not null)",
            ProductionEntityScanConfiguration.class, Fixtures.class);
        dispatcher = context().getBean(OperationDispatcher.class);
        dataSource = context().getBean(DataSource.class);
        jdbc = context().getBean(JdbcTemplate.class);
        transactionManager = context().getBean(PlatformTransactionManager.class);
    }

    @Test
    void jpaFlushBecomesVisibleToJooqAndJooqMutationNeedsJpaRefresh() {
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                EntityManager entityManager = probe.jpa().entityManager();
                Row row = new Row(1, "created by JPA");
                entityManager.persist(row);
                entityManager.flush();

                DSLContext db = probe.jooq().dsl();
                assertEquals("created by JPA", db.fetchValue(
                    "select label from groups.tx_joint_probe where id = ?", 1));
                assertEquals(1, db.execute(
                    "update groups.tx_joint_probe set label = ? where id = ?", "updated by jOOQ", 1));

                // The first-level JPA cache does not magically observe a direct SQL update.
                assertEquals("created by JPA", row.label);
                entityManager.refresh(row);
                assertEquals("updated by jOOQ", row.label);
                return TransactionOutcome.commit(null);
            });
            return null;
        });
        assertEquals("updated by jOOQ", jdbc.queryForObject(
            "select label from groups.tx_joint_probe where id = 1", String.class));
    }

    @Test
    void jooqAndJpaWritesCommitOrRollBackAsOnePhysicalUnit() {
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(10, "JPA"));
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (11, 'jOOQ')");
                return TransactionOutcome.commit(null);
            });
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(12, "rolled back JPA"));
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (13, 'rolled back jOOQ')");
                return TransactionOutcome.rollback(null);
            });
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(14, "abstained JPA"));
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (15, 'abstained jOOQ')");
                return TransactionOutcome.abstain(null);
            });
            return null;
        });
        assertEquals(2, countRows());
        assertTrue(exists(10));
        assertTrue(exists(11));
        for (int absent : new int[]{12, 13, 14, 15}) {
            assertFalse(exists(absent));
        }
    }

    @Test
    void independentConnectionDoesNotSeeUncommittedJpaOrJooqWrites() {
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(20, "not committed"));
                probe.jpa().entityManager().flush();
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (21, 'also pending')");
                assertEquals(0, countFromIndependentConnection());
                return TransactionOutcome.commit(null);
            });
            assertEquals(2, countFromIndependentConnection());
            return null;
        });
    }

    @Test
    void expectedBusinessFailureMayCommitAnIntentionalSideEffect() {
        String result = dispatcher.dispatch(Probe.class, probe -> probe.transactions().execute(() -> {
            probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (30, 'denied attempt')");
            return TransactionOutcome.commit("DENIED");
        }));
        assertEquals("DENIED", result);
        assertTrue(exists(30));
    }

    @Test
    void rollbackOnlyVetoPreventsMixedAdapterCommitAndNextSegmentIsHealthy() {
        dispatcher.dispatch(Probe.class, probe -> {
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(40, "JPA"));
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (41, 'jOOQ')");
                probe.transactions().markRollbackOnly();
                assertTrue(probe.transactions().isRollbackOnly());
                return TransactionOutcome.commit("should not succeed");
            }));
            probe.transactions().execute(() -> {
                assertFalse(probe.transactions().isRollbackOnly());
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (42, 'fresh')");
                return TransactionOutcome.commit(null);
            });
            return null;
        });
        assertFalse(exists(40));
        assertFalse(exists(41));
        assertTrue(exists(42));
    }

    @Test
    void callbackFailureRollsBackBothAdaptersAndManagerRemainsUsable() {
        IllegalArgumentException expected = new IllegalArgumentException("application failure");
        dispatcher.dispatch(Probe.class, probe -> {
            IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> probe.transactions().execute(() -> {
                    probe.jpa().entityManager().persist(new Row(50, "JPA"));
                    probe.jpa().entityManager().flush();
                    probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (51, 'jOOQ')");
                    throw expected;
                }));
            assertSame(expected, actual);
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(52, "recovered"));
                return TransactionOutcome.commit(null);
            });
            return null;
        });
        assertFalse(exists(50));
        assertFalse(exists(51));
        assertTrue(exists(52));
    }

    @Test
    void afterCommitFailurePropagatesEvenThoughRowsAreDurable() {
        dispatcher.dispatch(Probe.class, probe -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> probe.transactions().execute(() -> {
                    probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (60, 'committed')");
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            throw new IllegalStateException("afterCommit listener failed");
                        }
                    });
                    return TransactionOutcome.commit(null);
                }));
            assertEquals("afterCommit listener failed", failure.getMessage());
            // Spring's cleanup runs after completion callbacks; reuse cannot be confirmed.
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(
                () -> TransactionOutcome.commit("must not reuse")));
            return null;
        });
        assertTrue(exists(60));
        assertFalse(exists(61));
    }

    @Test
    void translatedDatabaseErrorMustExplicitlyPoisonItsSegment() {
        dispatcher.dispatch(Probe.class, probe -> {
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(() -> {
                DSLContext db = probe.jooq().dsl();
                db.execute("insert into groups.tx_joint_probe (id, label) values (70, 'first')");
                try {
                    db.execute("insert into groups.tx_joint_probe (id, label) values (70, 'duplicate')");
                    throw new AssertionError("Expected a unique-key constraint failure");
                } catch (DataAccessException expectedConstraint) {
                    probe.transactions().markRollbackOnly();
                }
                return TransactionOutcome.commit("translated failure");
            }));
            assertEquals("DUPLICATE", probe.transactions().execute(() -> {
                DSLContext db = probe.jooq().dsl();
                db.execute("insert into groups.tx_joint_probe (id, label) values (71, 'first')");
                try {
                    db.execute("insert into groups.tx_joint_probe (id, label) values (71, 'duplicate')");
                    throw new AssertionError("Expected a unique-key constraint failure");
                } catch (DataAccessException expectedConstraint) {
                    probe.transactions().markRollbackOnly();
                    return TransactionOutcome.rollback("DUPLICATE");
                }
            }));
            return null;
        });
        assertFalse(exists(70));
        assertFalse(exists(71));
    }

    @Test
    void anUnrelatedActiveSpringTransactionCannotOwnTheOperationCommit() {
        TransactionTemplate ambient = new TransactionTemplate(transactionManager);
        ambient.execute(status -> {
            dispatcher.dispatch(Probe.class, probe -> {
                assertThrows(IllegalStateException.class, () -> probe.transactions().execute(() -> {
                    probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (80, 'must not execute')");
                    return TransactionOutcome.commit(null);
                }));
                return null;
            });
            return null;
        });
        assertFalse(exists(80));
    }

    @Test
    void requiresNewCannotUseResourcesBelongingToSuspendedSegment() {
        assertSuspendedSegmentRejectsPersistence(TransactionDefinition.PROPAGATION_REQUIRES_NEW, 81);
    }

    @Test
    void notSupportedCannotUseResourcesBelongingToSuspendedSegment() {
        assertSuspendedSegmentRejectsPersistence(TransactionDefinition.PROPAGATION_NOT_SUPPORTED, 82);
    }

    private void assertSuspendedSegmentRejectsPersistence(int propagation, int rowId) {
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                DSLContext db = probe.jooq().dsl();
                EntityManager entityManager = probe.jpa().entityManager();
                db.execute("insert into groups.tx_joint_probe (id, label) values (?, ?)", rowId, "outer");

                TransactionTemplate nested = new TransactionTemplate(transactionManager);
                nested.setPropagationBehavior(propagation);
                nested.execute(status -> {
                    assertThrows(IllegalStateException.class, probe.transactions()::isRollbackOnly);
                    assertThrows(IllegalStateException.class, probe.transactions()::markRollbackOnly);
                    assertThrows(IllegalStateException.class, () -> probe.jooq().dsl());
                    assertThrows(IllegalStateException.class, () -> probe.jpa().entityManager());
                    assertGuardedResourceRejected(() -> db.execute(
                        "insert into groups.tx_joint_probe (id, label) values (?, ?)", rowId + 1000, "escaped"));
                    assertGuardedResourceRejected(() -> entityManager.find(Row.class, rowId));
                    return null;
                });

                // The original transaction has resumed: previously acquired handles
                // are valid again only while their own segment remains active.
                assertEquals("outer", db.fetchValue(
                    "select label from groups.tx_joint_probe where id = ?", rowId));
                assertEquals("outer", entityManager.find(Row.class, rowId).label);
                return TransactionOutcome.rollback(null);
            });
            return null;
        });
        assertFalse(exists(rowId));
        assertFalse(exists(rowId + 1000));
    }

    private static void assertGuardedResourceRejected(Runnable operation) {
        RuntimeException failure = assertThrows(RuntimeException.class, operation::run);
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof IllegalStateException,
            "Expected transaction-segment rejection, got " + failure);
    }

    @Test
    void commitTimeJpaConstraintFailureMustNotReportSuccessOrPermitReuse() {
        dispatcher.dispatch(Probe.class, probe -> {
            assertThrows(RuntimeException.class, () -> probe.transactions().execute(() -> {
                probe.jooq().dsl().execute("insert into groups.tx_joint_probe (id, label) values (90, 'jOOQ')");
                // Hibernate normally writes this entity on flush during commit.
                probe.jpa().entityManager().persist(new Row(90, "JPA duplicate"));
                return TransactionOutcome.commit(null);
            }));
            assertThrows(IllegalStateException.class, () -> probe.transactions().execute(
                () -> TransactionOutcome.commit(null)));
            return null;
        });
        assertFalse(exists(90));
    }

    private int countRows() {
        return jdbc.queryForObject("select count(*) from groups.tx_joint_probe", Integer.class);
    }

    private boolean exists(int id) {
        return jdbc.queryForObject("select count(*) from groups.tx_joint_probe where id = ?", Integer.class, id) == 1;
    }

    private int countFromIndependentConnection() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select count(*) from groups.tx_joint_probe");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new AssertionError("Cannot inspect independent PostgreSQL connection", failure);
        }
    }

    record Probe(Transactions transactions, JooqAccess jooq, JpaAccess jpa) { }

    @Entity(name = "JointProbeRow")
    @Table(name = "tx_joint_probe", schema = "groups")
    public static class Row {
        @Id
        private Integer id;
        @Column(nullable = false)
        private String label;

        protected Row() { }

        Row(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.boot.persistence.autoconfigure.EntityScan(
        basePackages = "de.hsh.favs.sharewise.shared.infra.transactions.internal")
    static class Fixtures {
        @Bean @Scope("operation")
        Probe probe(Transactions transactions, JooqAccess jooq, JpaAccess jpa) {
            return new Probe(transactions, jooq, jpa);
        }
    }
}

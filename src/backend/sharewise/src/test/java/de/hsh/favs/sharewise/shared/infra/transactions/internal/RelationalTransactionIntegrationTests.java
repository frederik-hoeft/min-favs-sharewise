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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * H2 integration coverage of Spring-managed jOOQ/JPA participation, independent
 * physical connections and exceptional paths. PostgreSQL acceptance is separate.
 */
@SpringJUnitConfig(RelationalTransactionIntegrationTests.Fixtures.class)
class RelationalTransactionIntegrationTests {

    @Autowired OperationDispatcher dispatcher;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetRows() {
        jdbc.update("delete from tx_joint_probe");
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
                    "select label from tx_joint_probe where id = ?", 1));
                assertEquals(1, db.execute(
                    "update tx_joint_probe set label = ? where id = ?", "updated by jOOQ", 1));

                // The first-level JPA cache does not magically observe a direct SQL update.
                assertEquals("created by JPA", row.label);
                entityManager.refresh(row);
                assertEquals("updated by jOOQ", row.label);
                return TransactionOutcome.commit(null);
            });
            return null;
        });
        assertEquals("updated by jOOQ", jdbc.queryForObject(
            "select label from tx_joint_probe where id = 1", String.class));
    }

    @Test
    void jooqAndJpaWritesCommitOrRollBackAsOnePhysicalUnit() {
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(10, "JPA"));
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (11, 'jOOQ')");
                return TransactionOutcome.commit(null);
            });
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(12, "rolled back JPA"));
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (13, 'rolled back jOOQ')");
                return TransactionOutcome.rollback(null);
            });
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().persist(new Row(14, "abstained JPA"));
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (15, 'abstained jOOQ')");
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
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (21, 'also pending')");
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
            probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (30, 'denied attempt')");
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
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (41, 'jOOQ')");
                probe.transactions().markRollbackOnly();
                assertTrue(probe.transactions().isRollbackOnly());
                return TransactionOutcome.commit("should not succeed");
            }));
            probe.transactions().execute(() -> {
                assertFalse(probe.transactions().isRollbackOnly());
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (42, 'fresh')");
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
                    probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (51, 'jOOQ')");
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
                    probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (60, 'committed')");
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
                db.execute("insert into tx_joint_probe (id, label) values (70, 'first')");
                try {
                    db.execute("insert into tx_joint_probe (id, label) values (70, 'duplicate')");
                    throw new AssertionError("Expected a unique-key constraint failure");
                } catch (DataAccessException expectedConstraint) {
                    probe.transactions().markRollbackOnly();
                }
                return TransactionOutcome.commit("translated failure");
            }));
            assertEquals("DUPLICATE", probe.transactions().execute(() -> {
                DSLContext db = probe.jooq().dsl();
                db.execute("insert into tx_joint_probe (id, label) values (71, 'first')");
                try {
                    db.execute("insert into tx_joint_probe (id, label) values (71, 'duplicate')");
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
                    probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (80, 'must not execute')");
                    return TransactionOutcome.commit(null);
                }));
                return null;
            });
            return null;
        });
        assertFalse(exists(80));
    }

    @Test
    void commitTimeJpaConstraintFailureMustNotReportSuccessOrPermitReuse() {
        dispatcher.dispatch(Probe.class, probe -> {
            assertThrows(RuntimeException.class, () -> probe.transactions().execute(() -> {
                probe.jooq().dsl().execute("insert into tx_joint_probe (id, label) values (90, 'jOOQ')");
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
        return jdbc.queryForObject("select count(*) from tx_joint_probe", Integer.class);
    }

    private boolean exists(int id) {
        return jdbc.queryForObject("select count(*) from tx_joint_probe where id = ?", Integer.class, id) == 1;
    }

    private int countFromIndependentConnection() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select count(*) from tx_joint_probe");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new AssertionError("Cannot inspect independent H2 connection", failure);
        }
    }

    record Probe(Transactions transactions, JooqAccess jooq, JpaAccess jpa) { }

    @Entity(name = "JointProbeRow")
    @Table(name = "tx_joint_probe")
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

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {
        "de.hsh.favs.sharewise.shared.infra.di.internal",
        "de.hsh.favs.sharewise.shared.infra.transactions.internal",
        "de.hsh.favs.sharewise.shared.infra.persistence.jooq.internal",
        "de.hsh.favs.sharewise.shared.infra.persistence.jpa.internal"
    })
    static class Fixtures {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:p13b_mixed;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(Row.class.getPackageName());
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
        Probe probe(Transactions transactions, JooqAccess jooq, JpaAccess jpa) {
            return new Probe(transactions, jooq, jpa);
        }
    }
}

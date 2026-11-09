package de.hsh.favs.sharewise.shared.infra.persistence.jpa.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TypedQuery;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Component-level H2 coverage; PostgreSQL interoperability remains in Phase D. */
@SpringJUnitConfig(JpaAccessIntegrationTests.Fixtures.class)
class JpaAccessIntegrationTests {

    @Autowired OperationDispatcher dispatcher;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DataSource dataSource;

    @BeforeEach
    void resetRows() {
        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                probe.jpa().entityManager().createNativeQuery("delete from tx_jpa_probe").executeUpdate();
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    @Test
    void usesCurrentJpaTransactionAndAllowsSequentialSegments() {
        dispatcher.dispatch(Probe.class, probe -> {
            assertThrows(IllegalStateException.class, () -> probe.jpa().entityManager());

            probe.transactions().execute(() -> {
                EntityManager em = probe.jpa().entityManager();
                em.persist(new ProbeRow(101, "Committed"));
                return TransactionOutcome.commit(null);
            });
            probe.transactions().execute(() -> {
                EntityManager em = probe.jpa().entityManager();
                assertEquals("Committed", em.find(ProbeRow.class, 101).name);
                em.persist(new ProbeRow(102, "Rolled back"));
                return TransactionOutcome.rollback(null);
            });
            probe.transactions().execute(() -> {
                EntityManager em = probe.jpa().entityManager();
                assertFalse(em.createQuery("select p from TxJpaProbe p where p.id = :id", ProbeRow.class)
                    .setParameter("id", 102).getResultList().stream().findAny().isPresent());
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    @Test
    void staleJpaFacadesAndFluentQueriesNeverRebindToLaterSegment() {
        dispatcher.dispatch(Probe.class, probe -> {
            AtomicReference<EntityManager> escapedEm = new AtomicReference<>();
            AtomicReference<TypedQuery<ProbeRow>> escapedQuery = new AtomicReference<>();
            probe.transactions().execute(() -> {
                EntityManager em = probe.jpa().entityManager();
                escapedEm.set(em);
                TypedQuery<ProbeRow> query = em.createQuery(
                    "select p from TxJpaProbe p where p.id = :id", ProbeRow.class);
                // Fluent calls must return the guard, not the underlying query.
                escapedQuery.set(query.setParameter("id", 301));
                assertTrue(escapedQuery.get().getResultList().isEmpty());
                assertThrows(UnsupportedOperationException.class, () -> em.unwrap(Object.class));
                assertThrows(UnsupportedOperationException.class, () -> em.getDelegate());
                assertThrows(UnsupportedOperationException.class, () -> query.unwrap(Object.class));
                return TransactionOutcome.commit(null);
            });
            assertThrows(IllegalStateException.class, () -> escapedEm.get().find(ProbeRow.class, 301));
            assertThrows(IllegalStateException.class, () -> escapedQuery.get().getResultList());

            probe.transactions().execute(() -> {
                assertThrows(IllegalStateException.class, () -> escapedEm.get().find(ProbeRow.class, 301));
                assertThrows(IllegalStateException.class, () -> escapedQuery.get().getResultList());
                assertTrue(probe.jpa().entityManager().createQuery(
                    "select p from TxJpaProbe p", ProbeRow.class).getResultList().isEmpty());
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    @Test
    void rejectsAnUnrelatedJdbcTransactionWithoutJpaPersistenceContext() {
        TransactionTemplate jdbcTransaction = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
        jdbcTransaction.execute(status -> {
            SpringJpaAccess wrongManagerAccess = new SpringJpaAccess(
                () -> () -> { }, entityManagerFactory);
            assertThrows(IllegalStateException.class, wrongManagerAccess::entityManager);
            return null;
        });
    }

    @Test
    void wrongScopeWrongThreadAndPostCloseAccessFail() {
        JpaAccess escaped = dispatcher.dispatch(Probe.class, Probe::jpa);
        assertThrows(IllegalStateException.class, escaped::entityManager);

        dispatcher.dispatch(Probe.class, probe -> {
            probe.transactions().execute(() -> {
                assertThrows(IllegalStateException.class, escaped::entityManager);
                FutureTask<Void> otherThread = new FutureTask<>(() -> {
                    assertThrows(IllegalStateException.class, probe.jpa()::entityManager);
                    return null;
                });
                Thread.ofVirtual().start(otherThread);
                try {
                    otherThread.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return TransactionOutcome.commit(null);
            });
            return null;
        });
    }

    record Probe(Transactions transactions, JpaAccess jpa) { }

    @Entity(name = "TxJpaProbe")
    @Table(name = "tx_jpa_probe")
    public static class ProbeRow {
        @Id
        private Integer id;
        private String name;

        protected ProbeRow() { }

        ProbeRow(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {
        "de.hsh.favs.sharewise.shared.infra.di.internal",
        "de.hsh.favs.sharewise.shared.infra.transactions.internal",
        "de.hsh.favs.sharewise.shared.infra.persistence.jpa.internal"
    })
    static class Fixtures {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:jpa_access_p13c;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(ProbeRow.class.getPackageName());
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean @Scope("operation")
        Probe probe(Transactions transactions, JpaAccess jpa) {
            return new Probe(transactions, jpa);
        }
    }
}

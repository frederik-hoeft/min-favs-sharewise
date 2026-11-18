package de.hsh.favs.sharewise;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Verifies that the actual MVC request pipeline does not retain a JPA context across segments. */
@SpringBootTest(classes = {ShareWiseApplication.class, TransactionWebScopeE2E.Fixtures.class,
    ProductionEntityScanConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.junit.jupiter.api.Tag("e2e")
class TransactionWebScopeE2E {

    private static PostgresTestDatabase database;

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        database = PostgresTestDatabase.createMigrated();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresTestDatabase.APPLICATION_USER);
        registry.add("spring.datasource.password", () -> PostgresTestDatabase.APPLICATION_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired MockMvc mvc;

    @Test
    void httpRequestDoesNotKeepJpaPersistenceContextAcrossSequentialSegments() throws Exception {
        mvc.perform(get("/__test/transaction-segment-isolation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.boundBefore").value(false))
            .andExpect(jsonPath("$.boundBetween").value(false))
            .andExpect(jsonPath("$.boundAfter").value(false))
            .andExpect(jsonPath("$.bothSegmentsBound").value(true))
            .andExpect(jsonPath("$.separatePersistenceContexts").value(true));
    }

    record SegmentTrace(boolean boundBefore, boolean boundBetween, boolean boundAfter,
                        boolean bothSegmentsBound, boolean separatePersistenceContexts) { }

    record Probe(Transactions transactions, JpaAccess jpa) { }

    @TestComponent
    @RestController
    static final class SegmentProbeController {
        private final OperationDispatcher dispatcher;
        private final EntityManagerFactory entityManagerFactory;

        SegmentProbeController(OperationDispatcher dispatcher, EntityManagerFactory entityManagerFactory) {
            this.dispatcher = dispatcher;
            this.entityManagerFactory = entityManagerFactory;
        }

        @GetMapping("/__test/transaction-segment-isolation")
        SegmentTrace checkSegments() {
            return dispatcher.dispatch(Probe.class, probe -> {
                boolean boundBefore = TransactionSynchronizationManager.hasResource(entityManagerFactory);
                Object first = probe.transactions().execute(() -> {
                    probe.jpa().entityManager().createNativeQuery("select 1").getSingleResult();
                    return TransactionOutcome.commit(
                        TransactionSynchronizationManager.getResource(entityManagerFactory));
                });
                boolean boundBetween = TransactionSynchronizationManager.hasResource(entityManagerFactory);
                Object second = probe.transactions().execute(() -> {
                    probe.jpa().entityManager().createNativeQuery("select 1").getSingleResult();
                    return TransactionOutcome.commit(
                        TransactionSynchronizationManager.getResource(entityManagerFactory));
                });
                boolean boundAfter = TransactionSynchronizationManager.hasResource(entityManagerFactory);
                return new SegmentTrace(boundBefore, boundBetween, boundAfter,
                    first != null && second != null, first != second);
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean(destroyMethod = "close")
        PostgresTestDatabase database() {
            return TransactionWebScopeE2E.database;
        }

        @Bean
        static BeanFactoryPostProcessor closeDataSourceBeforeDroppingDatabase() {
            return beanFactory -> {
                if (!beanFactory.containsBeanDefinition("dataSource")) {
                    return;
                }
                BeanDefinition dataSource = beanFactory.getBeanDefinition("dataSource");
                String[] dependencies = dataSource.getDependsOn() == null ? new String[0]
                    : dataSource.getDependsOn();
                dataSource.setDependsOn(Stream.concat(Arrays.stream(dependencies), Stream.of("database"))
                    .toArray(String[]::new));
            };
        }

        @Bean
        @Scope("operation")
        Probe transactionWebProbe(Transactions transactions, JpaAccess jpa) {
            return new Probe(transactions, jpa);
        }

        @Bean
        SegmentProbeController segmentProbeController(OperationDispatcher dispatcher,
                EntityManagerFactory entityManagerFactory) {
            return new SegmentProbeController(dispatcher, entityManagerFactory);
        }
    }
}

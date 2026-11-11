package de.hsh.favs.sharewise;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Verifies that the actual MVC request pipeline does not retain a JPA context across segments. */
@SpringBootTest(classes = {ShareWiseApplication.class, TransactionWebScopeIntegrationTests.Fixtures.class})
@AutoConfigureMockMvc
class TransactionWebScopeIntegrationTests {

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

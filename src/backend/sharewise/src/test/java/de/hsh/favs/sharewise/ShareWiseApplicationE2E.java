package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.config.ShareWiseProperties;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import org.junit.jupiter.api.BeforeEach;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.system.api.v1.SystemHealthController;
import de.hsh.favs.sharewise.system.api.v1.SystemHealthController.HealthResponse;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.context.TestComponent;

@org.junit.jupiter.api.Tag("e2e")
class ShareWiseApplicationE2E extends E2ETestBase {

    @BeforeEach
    void initialize() {
        start(ProductionEntityScanConfiguration.class);
        scopeFactory = context().getBean(ServiceScopeFactory.class);
        beanFactory = context().getBeanFactory();
        properties = context().getBean(ShareWiseProperties.class);
        healthController = context().getBean(SystemHealthController.class);
    }

    ServiceScopeFactory scopeFactory;

    ConfigurableListableBeanFactory beanFactory;

    ShareWiseProperties properties;

    SystemHealthController healthController;

    @Test
    void contextLoads() {
        assertNotNull(scopeFactory);
        assertNotNull(beanFactory.getRegisteredScope("operation"));
        assertEquals("sharewise", properties.name());
        assertEquals(50, properties.apiDefaultPageSize());

        ResponseEntity<HealthResponse> health = healthController.health();
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertNotNull(health.getBody());
        assertEquals("UP", health.getBody().status());
    }

    @Test
    void productionDiscoveryIncludesOnlyExplicitlyRegisteredTestComponents() {
        String[] testComponents = beanFactory.getBeanNamesForAnnotation(TestComponent.class);
        // @TestConfiguration is meta-annotated with @TestComponent. The entity-scan
        // configuration is deliberately passed to start(...) rather than discovered.
        assertEquals(1, testComponents.length);
        assertEquals(ProductionEntityScanConfiguration.class, beanFactory.getType(testComponents[0]));
    }

    @Test
    void transactionManagerCanBeResolvedInTheProductionApplicationContext() {
        try (ServiceScope scope = scopeFactory.createScope()) {
            scope.run(provider -> {
                assertNotNull(provider.getRequiredService(Transactions.class));
                return null;
            });
        }
    }

    @Test
    void springBootJooqTransactionProviderCannotOpenIndependentTransactions() {
        try (ServiceScope scope = scopeFactory.createScope()) {
            scope.run(provider -> {
                Transactions transactions = provider.getRequiredService(Transactions.class);
                JooqAccess jooq = provider.getRequiredService(JooqAccess.class);
                AtomicReference<DSLContext> stale = new AtomicReference<>();
                transactions.execute(() -> {
                    DSLContext db = jooq.dsl();
                    stale.set(db);
                    assertJooqTransactionEntryRejected(db);
                    return TransactionOutcome.commit(null);
                });
                assertJooqTransactionEntryRejected(stale.get());
                return null;
            });
        }
    }

    private static void assertJooqTransactionEntryRejected(DSLContext db) {
        AtomicBoolean callbackExecuted = new AtomicBoolean();
        assertThrows(IllegalStateException.class,
            () -> db.transaction(configuration -> callbackExecuted.set(true)));
        assertThrows(IllegalStateException.class,
            () -> db.transactionResult(configuration -> {
                callbackExecuted.set(true);
                return "unexpected";
            }));
        assertTrue(!callbackExecuted.get());
    }

    @Test
    void springBootJooqConfigurationParticipatesInExplicitTransactionSegments() {
        try (ServiceScope scope = scopeFactory.createScope()) {
            scope.run(provider -> {
                Transactions transactions = provider.getRequiredService(Transactions.class);
                JooqAccess jooq = provider.getRequiredService(JooqAccess.class);
                Integer result = transactions.execute(() -> TransactionOutcome.commit(
                    jooq.dsl().fetchOne("select 1").get(0, Integer.class)));
                org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(1), result);
                return null;
            });
        }
    }

}

package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ShareWiseApplicationTests {

    @Autowired
    ServiceScopeFactory scopeFactory;

    @Autowired
    ConfigurableListableBeanFactory beanFactory;

    @Test
    void contextLoads() {
        assertNotNull(scopeFactory);
        assertNotNull(beanFactory.getRegisteredScope("operation"));
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

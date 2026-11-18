package de.hsh.favs.sharewise.tests.postgres;

import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresApplicationFixture;
import de.hsh.favs.sharewise.testsupport.transactions.PostgresRollbackOperationHarness;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The real-commit application must never contain the test-only rollback transaction adapter. */
@Tag("e2e")
class PostgresProductionWiringE2E {

    @Test
    void rollbackAdapterIsNotRegisteredInRealCommitFixture() {
        try (PostgresApplicationFixture fixture = PostgresApplicationFixture.start(
                ShareWiseApplication.class, ProductionEntityScanConfiguration.class)) {
            assertEquals(0, fixture.context().getBeanNamesForType(RollbackTransactions.class).length);
            new OperationTestHarness(fixture.context().getBean(ServiceScopeFactory.class))
                .inOperationScope(scope -> {
                    assertFalse(scope.resolve(Transactions.class) instanceof RollbackTransactions);
                    return null;
                });
            assertThrows(IllegalArgumentException.class,
                () -> new PostgresRollbackOperationHarness(fixture));
        }
    }
}

package de.hsh.favs.sharewise.tests.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestRunner;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The real-commit application must never contain the test-only rollback transaction adapter. */
@Tag("e2e")
class PostgresProductionWiringE2E extends E2ETestBase {

    @Test
    void rollbackAdapterIsNotRegisteredInRealCommitFixture() {
        runServiceTest(Transactions.class, (transactions, fixture) -> {
            assertEquals(0, fixture.context().getBeanNamesForType(RollbackTransactions.class).length);
            assertFalse(transactions instanceof RollbackTransactions);
            assertThrows(IllegalArgumentException.class, () -> new IntegrationTestRunner(fixture));
        }, ProductionEntityScanConfiguration.class);
    }
}

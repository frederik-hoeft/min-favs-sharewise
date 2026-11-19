package de.hsh.favs.sharewise.testsupport.integration;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackTransactions;
import de.hsh.favs.sharewise.testsupport.transactions.RollbackPhysicalTransactionOwner;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes a single rollback-mode operation against a real PostgreSQL application context.
 * The Spring physical transaction starts before operation-scoped services are resolved; its
 * only permitted physical disposition is rollback, including when user assertions throw.
 */
public final class IntegrationTestRunner {

    private final ServiceScopeFactory scopes;
    private final PlatformTransactionManager physicalManager;
    private final DataSource dataSource;

    public IntegrationTestRunner(IntegrationTestContext fixture) {
        Objects.requireNonNull(fixture, "fixture");
        scopes = fixture.context().getBean(ServiceScopeFactory.class);
        physicalManager = fixture.context().getBean(PlatformTransactionManager.class);
        dataSource = fixture.dataSource();
        if (fixture.context().getBeanNamesForType(RollbackTransactions.class).length != 1) {
            throw new IllegalArgumentException("RollbackTransactionTestConfiguration must be explicitly selected");
        }
    }

    public <T> T inRollbackOperation(Function<IntegrationTestScope, T> assertion) {
        Objects.requireNonNull(assertion, "assertion");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A rollback operation must own its outer physical transaction");
        }
        TransactionTemplate transaction = new TransactionTemplate(physicalManager);
        return transaction.execute(status -> {
            if (!status.isNewTransaction() || !TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Rollback operation requires a new Spring transaction");
            }
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            if (!(resource instanceof ConnectionHolder holder)) {
                throw new IllegalStateException("No Spring-bound JDBC connection for rollback inspection");
            }
            RollbackPhysicalTransactionOwner witness =
                RollbackPhysicalTransactionOwner.register(dataSource, holder);
            try {
                return new OperationTestHarness(scopes).inOperationScope(operation -> {
                    IntegrationTestScope scope =
                        new IntegrationTestScope(operation, dataSource, witness);
                    try {
                        return assertion.apply(scope);
                    } finally {
                        scope.close();
                    }
                });
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}

package de.hsh.favs.sharewise.tests.configuration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.testsupport.transactions.RollbackPhysicalTransactionOwner;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Verifies owner provenance without opening a database connection. */
class RollbackPhysicalTransactionOwnerTests {

    private final DataSource dataSource = new DriverManagerDataSource();
    private final ConnectionHolder originalConnection = new ConnectionHolder(fakeConnection());

    @BeforeEach
    void bindOriginalTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, originalConnection);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void anArbitraryActiveTransactionIsNotAnIntegrationTestOwner() {
        assertThrows(IllegalStateException.class, RollbackPhysicalTransactionOwner::requireCurrentOwner);
    }

    @Test
    void registeredOwnerIsBoundToTheOriginalTransactionAndConnection() {
        RollbackPhysicalTransactionOwner owner =
            RollbackPhysicalTransactionOwner.register(dataSource, originalConnection);
        assertSame(owner, RollbackPhysicalTransactionOwner.requireCurrentOwner());
        owner.requireCurrent();
        assertThrows(IllegalStateException.class,
            () -> RollbackPhysicalTransactionOwner.register(dataSource, originalConnection));

        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(fakeConnection()));
        assertThrows(IllegalStateException.class, RollbackPhysicalTransactionOwner::requireCurrentOwner);
    }

    @Test
    void suspendedOwnerIsNotValidInAnotherActiveTransaction() {
        RollbackPhysicalTransactionOwner owner =
            RollbackPhysicalTransactionOwner.register(dataSource, originalConnection);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        assertThrows(IllegalStateException.class, owner::requireCurrent);
        assertThrows(IllegalStateException.class, RollbackPhysicalTransactionOwner::requireCurrentOwner);
    }

    private static Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (instance, method, arguments) -> {
                throw new UnsupportedOperationException("No JDBC operations are permitted in this unit test");
            });
    }
}

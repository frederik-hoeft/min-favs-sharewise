package de.hsh.favs.sharewise.testsupport.transactions;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Identifies the original physical transaction opened by the Integration test runner.
 * Spring suspends registered synchronizations when entering REQUIRES_NEW or NOT_SUPPORTED;
 * a different active Spring transaction must never qualify as the rollback owner.
 */
public final class RollbackPhysicalTransactionOwner implements TransactionSynchronization {

    private final DataSource dataSource;
    private final ConnectionHolder connectionHolder;
    private final Thread ownerThread;

    private RollbackPhysicalTransactionOwner(DataSource dataSource, ConnectionHolder connectionHolder) {
        this.dataSource = dataSource;
        this.connectionHolder = connectionHolder;
        ownerThread = Thread.currentThread();
    }

    /** Register the owner before opening an application operation scope. */
    public static RollbackPhysicalTransactionOwner register(
            DataSource dataSource, ConnectionHolder connectionHolder) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(connectionHolder, "connectionHolder");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResource(dataSource) != connectionHolder) {
            throw new IllegalStateException("A rollback owner requires the original Spring-bound transaction");
        }
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof RollbackPhysicalTransactionOwner) {
                throw new IllegalStateException("The physical transaction already has a rollback owner");
            }
        }
        RollbackPhysicalTransactionOwner owner =
            new RollbackPhysicalTransactionOwner(dataSource, connectionHolder);
        TransactionSynchronizationManager.registerSynchronization(owner);
        return owner;
    }

    /** Resolve only an owner that belongs to the currently active Spring transaction. */
    public static RollbackPhysicalTransactionOwner requireCurrentOwner() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof RollbackPhysicalTransactionOwner owner) {
                    owner.requireCurrent();
                    return owner;
                }
            }
        }
        throw new IllegalStateException("Rollback mode requires the harness-owned physical transaction");
    }

    /** Reject suspended, replaced or completed transactions, including before the first logical segment. */
    public void requireCurrent() {
        if (Thread.currentThread() != ownerThread
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(this)
                || TransactionSynchronizationManager.getResource(dataSource) != connectionHolder) {
            throw new IllegalStateException("The harness-owned physical transaction is no longer active");
        }
    }
}

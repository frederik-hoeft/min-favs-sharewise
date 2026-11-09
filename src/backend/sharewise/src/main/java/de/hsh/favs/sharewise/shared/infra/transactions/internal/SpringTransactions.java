package de.hsh.favs.sharewise.shared.infra.transactions.internal;

import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One operation-scoped manager; Spring remains the sole physical transaction authority. */
@Component
@Scope("operation")
final class SpringTransactions implements Transactions {

    private final TransactionTemplate template;
    private TransactionStatus activeStatus;

    SpringTransactions(PlatformTransactionManager transactionManager) {
        template = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        if (activeStatus != null) {
            throw new IllegalStateException("Cannot start a nested transaction segment");
        }
        // A joined physical transaction could defer the outcome beyond this method's return.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Cannot execute inside an unrelated Spring transaction");
        }

        return template.execute(status -> {
            if (!status.isNewTransaction()) {
                throw new IllegalStateException("Transaction segment did not own a new physical transaction");
            }
            activeStatus = status;
            try {
                TransactionOutcome<T> outcome = Objects.requireNonNull(operation.get(), "transaction outcome");
                switch (outcome.decision()) {
                    case COMMIT -> {
                        if (status.isRollbackOnly()) {
                            throw new IllegalStateException("Cannot commit a rollback-only transaction segment");
                        }
                    }
                    case ROLLBACK, ABSTAIN -> status.setRollbackOnly();
                }
                return outcome.result();
            } finally {
                activeStatus = null;
            }
        });
    }

    @Override
    public void markRollbackOnly() {
        requireActiveStatus().setRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        return requireActiveStatus().isRollbackOnly();
    }

    private TransactionStatus requireActiveStatus() {
        if (activeStatus == null) {
            throw new IllegalStateException("No transaction segment is active");
        }
        return activeStatus;
    }
}

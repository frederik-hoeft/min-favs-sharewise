package de.hsh.favs.sharewise.shared.infra.transactions.internal;

import de.hsh.favs.sharewise.shared.infra.di.internal.OperationScopeAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One operation-scoped manager; Spring remains the sole physical transaction authority. */
@Component
@Scope("operation")
final class SpringTransactions implements Transactions {

    private enum State { IDLE, STARTING, ACTIVE, FINALIZING, FAILED }

    private final OperationScopeAccess.ScopeLease scopeLease;
    private final TransactionTemplate template;
    private State state = State.IDLE;
    private TransactionStatus activeStatus;

    SpringTransactions(PlatformTransactionManager transactionManager, OperationScopeAccess scopes) {
        template = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        scopeLease = Objects.requireNonNull(scopes).captureCurrent();
    }

    @Override
    public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
        scopeLease.requireCurrent();
        Objects.requireNonNull(operation, "operation");
        if (state != State.IDLE) {
            throw new IllegalStateException("Transaction manager is already executing or cannot be reused");
        }
        // Joining an existing transaction or synchronization would defer our claimed finalization.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Cannot execute inside an unrelated Spring transaction");
        }

        SegmentCompletion completion = new SegmentCompletion();
        state = State.STARTING;
        try {
            T result = template.execute(status -> executeSegment(status, operation, completion));
            finish(completion);
            return result;
        } catch (RuntimeException | Error failure) {
            finishAfterFailure(completion, failure);
            throw failure;
        }
    }

    private <T> T executeSegment(TransactionStatus status, Supplier<TransactionOutcome<T>> operation,
            SegmentCompletion completion) {
        if (!status.isNewTransaction() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Transaction segment requires a new, synchronized physical transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(completion);
        activeStatus = status;
        state = State.ACTIVE;
        try {
            TransactionOutcome<T> outcome = Objects.requireNonNull(operation.get(), "transaction outcome");
            switch (outcome.decision()) {
                case COMMIT -> {
                    if (status.isRollbackOnly()) {
                        throw new IllegalStateException("Cannot commit a rollback-only transaction segment");
                    }
                    completion.requested = TransactionDecision.COMMIT;
                }
                case ROLLBACK, ABSTAIN -> {
                    completion.requested = outcome.decision();
                    status.setRollbackOnly();
                }
            }
            return outcome.result();
        } catch (RuntimeException | Error failure) {
            completion.callbackFailure = failure;
            throw failure;
        } finally {
            activeStatus = null;
            state = State.FINALIZING;
        }
    }

    /** A successful execute must have an observed, definitive physical completion. */
    private void finish(SegmentCompletion completion) {
        if (!completion.matchesRequestedDisposition()) {
            state = State.FAILED;
            throw new IllegalStateException("Transaction completed without its requested physical disposition");
        }
        state = State.IDLE;
    }

    private void finishAfterFailure(SegmentCompletion completion, Throwable failure) {
        // Spring may replace the application exception if rollback itself fails. Retain both.
        if (completion.callbackFailure != null && completion.callbackFailure != failure) {
            failure.addSuppressed(completion.callbackFailure);
        }
        // A failed COMMIT may have reached the database even when Spring subsequently
        // attempted rollback and reported ROLLED_BACK. Do not mistake that for proof
        // that the commit never happened. A callback failure has no requested disposition.
        state = completion.isSafeAfterFailure() ? State.IDLE : State.FAILED;
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
        scopeLease.requireCurrent();
        if (state != State.ACTIVE || activeStatus == null) {
            throw new IllegalStateException("No active, writable transaction segment");
        }
        return activeStatus;
    }

    /** Physical completion is observed independently of the application's requested outcome. */
    private static final class SegmentCompletion implements TransactionSynchronization {
        private int completionStatus = -1;
        private Throwable callbackFailure;
        private TransactionDecision requested;

        @Override
        public void afterCompletion(int status) {
            completionStatus = status;
        }

        boolean matchesRequestedDisposition() {
            return requested == TransactionDecision.COMMIT
                ? completionStatus == STATUS_COMMITTED
                : requested != null && completionStatus == STATUS_ROLLED_BACK;
        }

        boolean isSafeAfterFailure() {
            return completionStatus == STATUS_COMMITTED
                ? requested == TransactionDecision.COMMIT
                : completionStatus == STATUS_ROLLED_BACK && requested != TransactionDecision.COMMIT;
        }
    }
}

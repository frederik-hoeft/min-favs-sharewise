package de.hsh.favs.sharewise.testsupport.transactions;

import de.hsh.favs.sharewise.shared.infra.di.internal.OperationScopeAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One operation-scoped logical segment inside a test-owned physical Spring transaction.
 * This substitute never starts or finalizes a physical transaction. It deliberately cannot
 * model sequential segments, which require the real production transaction manager.
 */
public final class RollbackTransactions implements Transactions, TransactionSegmentAccess {

    private enum State { READY, ACTIVE, COMPLETED }

    private final OperationScopeAccess.ScopeLease scopeLease;
    private State state = State.READY;
    private Object segmentIdentity;
    private TransactionSynchronization physicalWitness;
    private boolean rollbackOnly;
    private TransactionObservation observation;

    RollbackTransactions(OperationScopeAccess scopes) {
        scopeLease = Objects.requireNonNull(scopes).captureCurrent();
    }

    @Override
    public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
        scopeLease.requireCurrent();
        Objects.requireNonNull(operation, "operation");
        if (state != State.READY) {
            // A swallowed nested invocation must not make the enclosing COMMIT look safe.
            if (state == State.ACTIVE) {
                rollbackOnly = true;
            }
            throw new IllegalStateException("Rollback-mode tests permit exactly one logical transaction segment");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Rollback mode requires a pre-existing test-owned Spring transaction");
        }

        // Spring suspends and restores registered synchronizations when changing physical
        // transactions. Identity of this witness prevents leases from following a suspended
        // test transaction into REQUIRES_NEW or NOT_SUPPORTED, even on the same thread.
        physicalWitness = new TransactionSynchronization() { };
        TransactionSynchronizationManager.registerSynchronization(physicalWitness);
        segmentIdentity = new Object();
        state = State.ACTIVE;

        TransactionDecision requested = null;
        Object result = null;
        Throwable failure = null;
        try {
            TransactionOutcome<T> outcome = Objects.requireNonNull(operation.get(), "transaction outcome");
            requested = outcome.decision();
            result = outcome.result();
            if (requested == TransactionDecision.COMMIT && rollbackOnly) {
                throw new IllegalStateException("Cannot commit a rollback-only logical transaction segment");
            }
            return outcome.result();
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            observation = new TransactionObservation(requested, result, rollbackOnly, failure, true);
            state = State.COMPLETED;
        }
    }

    @Override
    public SegmentLease captureCurrentSegment() {
        requireActiveSegment();
        Object identity = segmentIdentity;
        return () -> {
            requireActiveSegment();
            if (segmentIdentity != identity) {
                throw new IllegalStateException("Persistence resource belongs to another logical segment");
            }
        };
    }

    @Override
    public void markRollbackOnly() {
        requireActiveSegment();
        rollbackOnly = true;
    }

    @Override
    public boolean isRollbackOnly() {
        requireActiveSegment();
        return rollbackOnly;
    }

    /** A completed logical observation, not evidence of a physical commit or rollback. */
    public TransactionObservation observation() {
        scopeLease.requireCurrent();
        if (state != State.COMPLETED) {
            throw new IllegalStateException("No completed logical segment to inspect");
        }
        return observation;
    }

    private void requireActiveSegment() {
        scopeLease.requireCurrent();
        if (state != State.ACTIVE) {
            throw new IllegalStateException("No active logical transaction segment");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(physicalWitness)) {
            throw new IllegalStateException("The owning test transaction is suspended or no longer active");
        }
    }
}

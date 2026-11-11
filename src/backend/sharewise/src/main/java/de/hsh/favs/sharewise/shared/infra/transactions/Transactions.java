package de.hsh.favs.sharewise.shared.infra.transactions;

import java.util.function.Supplier;

/**
 * Executes explicitly owned, sequential database transaction segments within an operation.
 *
 * <p>The enclosing operation DI scope is independent of each physical transaction segment.
 * An outcome determines the final disposition of one segment; the returned application result
 * is available only after Spring completes that disposition.
 */
public interface Transactions {

    <T> T execute(Supplier<TransactionOutcome<T>> operation);

    /** Prevents the currently active segment from committing. */
    void markRollbackOnly();

    /** Whether the currently active segment has been marked rollback-only. */
    boolean isRollbackOnly();
}

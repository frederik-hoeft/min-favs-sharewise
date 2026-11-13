package de.hsh.favs.sharewise.shared.infra.transactions;

import java.util.Objects;

/**
 * An immutable operation result and the requested final disposition of its physical segment.
 * Constructing an outcome does not affect any transaction; only the outcome returned from
 * {@link Transactions#execute(java.util.function.Supplier)} selects the disposition.
 *
 * @param result application result, possibly {@code null}
 * @param decision required transaction disposition
 * @param <T> application result type
 */
public record TransactionOutcome<T>(T result, TransactionDecision decision) {

    public TransactionOutcome {
        Objects.requireNonNull(decision, "decision");
    }

    public static <T> TransactionOutcome<T> commit(T result) {
        return new TransactionOutcome<>(result, TransactionDecision.COMMIT);
    }

    public static <T> TransactionOutcome<T> rollback(T result) {
        return new TransactionOutcome<>(result, TransactionDecision.ROLLBACK);
    }

    public static <T> TransactionOutcome<T> abstain(T result) {
        return new TransactionOutcome<>(result, TransactionDecision.ABSTAIN);
    }
}

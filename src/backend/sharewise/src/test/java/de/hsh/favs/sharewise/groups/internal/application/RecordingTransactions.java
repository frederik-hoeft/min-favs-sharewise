package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Test-only {@link Transactions} that runs each segment directly and records its requested
 * disposition. The production manager's physical behavior has its own contract tests.
 */
final class RecordingTransactions implements Transactions {

    private final Log log;
    private boolean active;
    private boolean rollbackOnly;

    RecordingTransactions(Log log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
        if (active) {
            throw new IllegalStateException("nested segment");
        }
        active = true;
        rollbackOnly = false;
        try {
            TransactionOutcome<T> outcome = operation.get();
            log.record(rollbackOnly ? TransactionDecision.ROLLBACK : outcome.decision());
            return outcome.result();
        } finally {
            active = false;
        }
    }

    @Override
    public void markRollbackOnly() {
        rollbackOnly = true;
    }

    @Override
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    /** Singleton record of segment dispositions across operations. */
    static final class Log {
        private final List<TransactionDecision> decisions = new ArrayList<>();

        synchronized void record(TransactionDecision decision) {
            decisions.add(decision);
        }

        synchronized List<TransactionDecision> decisions() {
            return List.copyOf(decisions);
        }

        synchronized void reset() {
            decisions.clear();
        }
    }
}

package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.result.Result;

/** Maps Group operation results to the disposition of their transaction segment. */
final class GroupOutcomes {

    private GroupOutcomes() {
    }

    /** Commits a successful mutation; rolls back every expected failure. */
    static <T> TransactionOutcome<Result<T, GroupFailure>> mutation(Result<T, GroupFailure> result) {
        return result.isSuccess() ? TransactionOutcome.commit(result) : TransactionOutcome.rollback(result);
    }

    /** A successful read requests no persistence; an expected failure is a rejection. */
    static <T> TransactionOutcome<Result<T, GroupFailure>> read(Result<T, GroupFailure> result) {
        return result.isSuccess() ? TransactionOutcome.abstain(result) : TransactionOutcome.rollback(result);
    }
}

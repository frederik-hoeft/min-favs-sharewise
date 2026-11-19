package de.hsh.favs.sharewise.testsupport.transactions;

import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;

/**
 * Logical execution result retained for assertions while the test's physical transaction
 * remains open. A null decision indicates that the callback did not return an outcome.
 * Neither a requested COMMIT nor completed=true means that anything was physically committed.
 *
 * @param requestedDecision final application intent, or null if no outcome was returned
 * @param result application result, potentially null
 * @param rollbackOnly whether a participant marked the logical segment rollback-only
 * @param failure propagated callback or logical-validation failure, or null on normal return
 * @param completed whether the logical callback has exited
 */
public record TransactionObservation(TransactionDecision requestedDecision, Object result,
        boolean rollbackOnly, Throwable failure, boolean completed) { }

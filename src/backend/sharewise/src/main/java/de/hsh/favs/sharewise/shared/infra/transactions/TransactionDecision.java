package de.hsh.favs.sharewise.shared.infra.transactions;

/** Final physical disposition requested for one transaction segment. */
public enum TransactionDecision {
    COMMIT,
    ROLLBACK,
    ABSTAIN
}

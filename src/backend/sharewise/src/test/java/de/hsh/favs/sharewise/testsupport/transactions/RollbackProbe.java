package de.hsh.favs.sharewise.testsupport.transactions;

import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;

/** A single operation-scoped view of the rollback-mode collaborators. */
public record RollbackProbe(Transactions transactions, TransactionSegmentAccess segments,
        JooqAccess jooq, JpaAccess jpa) { }

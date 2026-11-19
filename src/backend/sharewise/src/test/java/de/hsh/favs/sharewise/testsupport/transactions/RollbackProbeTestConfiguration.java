package de.hsh.favs.sharewise.testsupport.transactions;

import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/** Explicit opt-in for the collaborator probe used by rollback integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class RollbackProbeTestConfiguration {
    @Bean
    @Scope("operation")
    RollbackProbe rollbackProbe(Transactions transactions, TransactionSegmentAccess segments,
            JooqAccess jooq, JpaAccess jpa) {
        return new RollbackProbe(transactions, segments, jooq, jpa);
    }
}

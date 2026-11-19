package de.hsh.favs.sharewise.testsupport.transactions;

import de.hsh.favs.sharewise.shared.infra.di.internal.OperationScopeAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/** Explicit opt-in: never imported or scanned by production/real-commit contexts. */
@TestConfiguration(proxyBeanMethods = false)
public class RollbackTransactionTestConfiguration {

    // One scoped bean implements BOTH transaction-facing interfaces. @Primary selects it
    // over the production manager without replacing a scoped bean with a singleton.
    @Bean
    @Primary
    @Scope("operation")
    RollbackTransactions rollbackTransactions(OperationScopeAccess scopes) {
        return new RollbackTransactions(scopes);
    }
}

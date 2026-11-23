package de.hsh.favs.sharewise.identity.internal.verification;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.IdentityPort;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/** IAM's local authority-port wiring. The database query adapter is supplied in Phase B. */
@Configuration(proxyBeanMethods = false)
class IdentityVerificationConfiguration {

    @Bean
    @Scope("operation")
    IamIdentityVerificationService iamIdentityVerificationService(Transactions transactions,
            IdentityEligibilityQuery eligibility, @Qualifier("iamClock") Clock clock) {
        return new IamIdentityVerificationService(transactions, eligibility, clock);
    }

    @Bean
    @Scope("operation")
    IdentityPort identityPort(IdentityContext identity, IamIdentityVerificationService verifier) {
        return new LocalIdentityPort(identity, verifier);
    }
}

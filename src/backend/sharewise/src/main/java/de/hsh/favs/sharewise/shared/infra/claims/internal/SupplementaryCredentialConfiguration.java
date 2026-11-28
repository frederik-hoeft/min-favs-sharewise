package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
import de.hsh.favs.sharewise.shared.infra.claims.SupplementaryCredentialInputs;
import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/** Transfers independent supplementary proofs to the operation, never to an ambient principal. */
@Configuration(proxyBeanMethods = false)
public class SupplementaryCredentialConfiguration {
    @Bean
    @Scope("operation")
    SupplementaryState supplementaryState() { return new SupplementaryState(); }

    @Bean
    ScopedServiceInitializer<SupplementaryState, SupplementaryCredentialInputs> supplementaryInitializer() {
        return new ScopedServiceInitializer<>() {
            @Override public Class<SupplementaryState> configurationType() { return SupplementaryState.class; }
            @Override public Class<SupplementaryCredentialInputs> inputType() { return SupplementaryCredentialInputs.class; }
            @Override public void initialize(SupplementaryState configuration, Optional<SupplementaryCredentialInputs> input) {
                configuration.seed(input.orElseGet(() -> new SupplementaryCredentialInputs(List.of())));
            }
        };
    }

    @Bean
    @Scope("operation")
    AuthorizationCredentialProvider supplementaryProvider(SupplementaryState state) {
        return () -> state.inputs().candidates();
    }

    static final class SupplementaryState extends OperationScopedConfiguration {
        private SupplementaryCredentialInputs inputs;

        void seed(SupplementaryCredentialInputs input) {
            requireInitializing();
            if (inputs != null) {
                throw new IllegalStateException("Supplementary input already initialized");
            }
            inputs = input;
        }

        @Override protected void validate() {
            if (inputs == null) {
                throw new IllegalStateException("Supplementary input not initialized");
            }
        }

        SupplementaryCredentialInputs inputs() {
            requireReady();
            return inputs;
        }
    }
}

package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.hsh.favs.sharewise.identity.internal.auth.LoginCommand;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.AuthCredentialsRequest;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthFactoryWiringTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(PasswordPolicy.class, PasswordPolicy::initial)
            .withUserConfiguration(RegistrationCommandFactory.class, LoginCommandFactory.class);

    @Test
    void enabledAuthenticationPublishesBothTypedInputFactories() {
        runner.withPropertyValues("sharewise.iam.auth.enabled=true")
                .withBean(FactoryConsumer.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    FactoryConsumer consumer = context.getBean(FactoryConsumer.class);
                    AuthCredentialsRequest registration = new AuthCredentialsRequest(
                            "One@example.org", "password1");
                    assertTrue(consumer.registrations.createFrom(registration).isSuccess());
                    assertEquals("one@example.org", consumer.logins.createFrom(
                            new AuthCredentialsRequest("ONE@example.org", "x"))
                            .fold(command -> command.email().normalized(), errors -> "unexpected"));
                });
    }

    @Test
    void disabledAuthenticationDoesNotPublishProductionCredentialInputFactories() {
        runner.run(context -> {
            assertFalse(context.containsBean("registrationCommandFactory"));
            assertFalse(context.containsBean("loginCommandFactory"));
        });
    }

    static final class FactoryConsumer {
        final DomainObjectFactory<RegistrationCommand, AuthCredentialsRequest> registrations;
        final DomainObjectFactory<LoginCommand, AuthCredentialsRequest> logins;

        FactoryConsumer(
                @Qualifier("registrationCommandFactory")
                DomainObjectFactory<RegistrationCommand, AuthCredentialsRequest> registrations,
                @Qualifier("loginCommandFactory")
                DomainObjectFactory<LoginCommand, AuthCredentialsRequest> logins) {
            this.registrations = registrations;
            this.logins = logins;
        }
    }
}

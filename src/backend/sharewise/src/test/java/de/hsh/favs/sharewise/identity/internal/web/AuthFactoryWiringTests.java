package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.hsh.favs.sharewise.identity.internal.auth.ChangePasswordCommand;
import de.hsh.favs.sharewise.identity.internal.auth.LoginCommand;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.profile.DisplayName;
import de.hsh.favs.sharewise.identity.internal.web.dto.AccountProfileUpdateRequest;
import de.hsh.favs.sharewise.identity.internal.web.dto.AuthCredentialsRequest;
import de.hsh.favs.sharewise.identity.internal.web.dto.RegistrationRequest;
import de.hsh.favs.sharewise.identity.internal.web.dto.ChangePasswordRequest;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthFactoryWiringTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(PasswordPolicy.class, PasswordPolicy::initial)
            .withUserConfiguration(RegistrationCommandFactory.class, LoginCommandFactory.class,
                    AccountProfileUpdateFactory.class, PasswordChangeCommandFactory.class);

    @Test
    void enabledAuthenticationPublishesTypedInputFactories() {
        runner.withPropertyValues("sharewise.iam.auth.enabled=true")
                .withBean(FactoryConsumer.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    FactoryConsumer consumer = context.getBean(FactoryConsumer.class);
                    RegistrationRequest registration = new RegistrationRequest(
                            "One@example.org", "password1", "Test user");
                    assertTrue(consumer.registrations.createFrom(registration).isSuccess());
                    assertEquals("one@example.org", consumer.logins.createFrom(
                            new AuthCredentialsRequest("ONE@example.org", "x"))
                            .fold(command -> command.email().normalized(), errors -> "unexpected"));
                    AccountProfileUpdateRequest update = new AccountProfileUpdateRequest("Alice");
                    assertEquals("Alice", consumer.updates.createFrom(update)
                            .fold(DisplayName::value, errors -> "unexpected"));
                    assertTrue(consumer.passwordChanges.createFrom(
                            new ChangePasswordRequest("current-password", "new-password")).isSuccess());
                });
    }

    @Test
    void disabledAuthenticationDoesNotPublishProductionCredentialInputFactories() {
        runner.run(context -> {
            assertFalse(context.containsBean("registrationCommandFactory"));
            assertFalse(context.containsBean("loginCommandFactory"));
            assertFalse(context.containsBean("accountProfileUpdateFactory"));
            assertFalse(context.containsBean("passwordChangeCommandFactory"));
        });
    }

    static final class FactoryConsumer {
        final DomainObjectFactory<RegistrationCommand, RegistrationRequest> registrations;
        final DomainObjectFactory<LoginCommand, AuthCredentialsRequest> logins;
        final DomainObjectFactory<DisplayName, AccountProfileUpdateRequest> updates;
        final DomainObjectFactory<ChangePasswordCommand, ChangePasswordRequest> passwordChanges;

        FactoryConsumer(
                @Qualifier("registrationCommandFactory")
                DomainObjectFactory<RegistrationCommand, RegistrationRequest> registrations,
                @Qualifier("loginCommandFactory")
                DomainObjectFactory<LoginCommand, AuthCredentialsRequest> logins,
                @Qualifier("accountProfileUpdateFactory")
                DomainObjectFactory<DisplayName, AccountProfileUpdateRequest> updates,
                @Qualifier("passwordChangeCommandFactory")
                DomainObjectFactory<ChangePasswordCommand, ChangePasswordRequest> passwordChanges) {
            this.registrations = registrations;
            this.logins = logins;
            this.updates = updates;
            this.passwordChanges = passwordChanges;
        }
    }
}

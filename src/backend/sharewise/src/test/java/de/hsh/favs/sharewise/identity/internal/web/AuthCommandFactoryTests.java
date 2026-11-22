package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.LoginCommand;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.AuthCredentialsRequest;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthCommandFactoryTests {

    private static final PasswordPolicy POLICY = PasswordPolicy.initial();
    private final DomainObjectFactory<RegistrationCommand, AuthCredentialsRequest> registrations =
            new RegistrationCommandFactory(POLICY);
    private final DomainObjectFactory<LoginCommand, AuthCredentialsRequest> logins =
            new LoginCommandFactory(POLICY);

    @Test
    void nullPayloadMapsToRequestErrorWithNoTransportDependencyInFactory() {
        Result<RegistrationCommand, ValidationErrors> failed = registrations.createFrom(null);
        assertTrue(failed.isFailure());
        assertEquals("request", failed.fold(ok -> "unexpected", errors -> errors.errors().getFirst().field()));
        assertEquals("required", failed.fold(ok -> "unexpected", errors -> errors.errors().getFirst().code()));
    }

    @Test
    void errorMappingPreservesFieldOrderCodeAndMessages() {
        Result<RegistrationCommand, ValidationErrors> failed = registrations.createFrom(
                new AuthCredentialsRequest("invalid", "short"));
        assertTrue(failed.isFailure());
        RequestValidationException mapped = failed.fold(
                ignored -> { throw new AssertionError("Expected errors"); },
                RequestValidationException::from);
        assertEquals(List.of("email", "password"), mapped.fieldErrors().stream()
                .map(detail -> detail.field()).toList());
        assertEquals(List.of("invalid", "invalid"), mapped.fieldErrors().stream()
                .map(detail -> detail.code()).toList());
        assertEquals(List.of("Enter a valid email address.", "Enter a valid password."),
                mapped.fieldErrors().stream().map(detail -> detail.message()).toList());
        assertFalse(mapped.getMessage().contains("short"));
    }

    @Test
    void registrationAndLoginUseDistinctPasswordPoliciesAndSameEmailSemantics() {
        AuthCredentialsRequest oldPassword = new AuthCredentialsRequest(" ONE@example.org ", "legacy");
        assertTrue(registrations.createFrom(oldPassword).isFailure());
        Result<LoginCommand, ValidationErrors> accepted = logins.createFrom(oldPassword);
        assertTrue(accepted.isSuccess());
        assertEquals("one@example.org", accepted.fold(
                command -> command.email().normalized(), ignored -> "unexpected"));
        assertTrue(registrations.createFrom(new AuthCredentialsRequest(
                " ONE@example.org ", "password1")).isSuccess());
    }
}

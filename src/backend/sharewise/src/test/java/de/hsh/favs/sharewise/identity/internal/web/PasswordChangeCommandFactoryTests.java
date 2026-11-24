package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.ChangePasswordCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.ChangePasswordRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import org.junit.jupiter.api.Test;

class PasswordChangeCommandFactoryTests {

    private final DomainObjectFactory<ChangePasswordCommand, ChangePasswordRequest> factory =
            new PasswordChangeCommandFactory(PasswordPolicy.initial());

    @Test
    void createsCommandUsingTheExistingDomainPolicy() {
        Result<ChangePasswordCommand, ValidationErrors> result = factory.createFrom(
                new ChangePasswordRequest("old-password-123", "replacement-password-123"));
        assertTrue(result.isSuccess());
        ChangePasswordCommand command = result.fold(value -> value,
                errors -> { throw new AssertionError("Unexpected validation failure"); });
        assertEquals("old-password-123", command.currentPassword());
        assertEquals("replacement-password-123", command.newPassword());
        assertFalse(command.toString().contains("old-password-123"));
    }

    @Test
    void rejectsMissingRequestWithoutInvokingTheService() {
        assertFailure(null, "request", "required");
    }

    @Test
    void delegatesFieldValidationAndUnchangedPasswordChecks() {
        assertFailure(new ChangePasswordRequest(null, "new-password-123"), "currentPassword", "invalid");
        assertFailure(new ChangePasswordRequest("old-password-123", "short"), "newPassword", "invalid");
        assertFailure(new ChangePasswordRequest("old-password-123", "old-password-123"),
                "newPassword", "unchanged");
    }

    private void assertFailure(ChangePasswordRequest input, String field, String code) {
        Result<ChangePasswordCommand, ValidationErrors> result = factory.createFrom(input);
        assertTrue(result.isFailure());
        ValidationErrors errors = result.fold(
                value -> { throw new AssertionError("Expected validation failure"); },
                value -> value);
        assertEquals(field, errors.errors().getFirst().field());
        assertEquals(code, errors.errors().getFirst().code());
    }
}

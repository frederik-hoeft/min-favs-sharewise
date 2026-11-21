package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialCommandValidationTests {

    private static final PasswordPolicy POLICY = PasswordPolicy.initial();

    @Test
    void registrationAggregatesIndependentFieldErrors() {
        Result<RegistrationCommand, ValidationErrors> result = RegistrationCommand.tryFrom(
                "invalid", "seven77", POLICY);
        assertTrue(result.isFailure());
        assertEquals(List.of("email", "password"), result.fold(
                ignored -> List.<String>of(),
                errors -> errors.errors().stream().map(error -> error.field()).toList()));
        assertEquals(List.of("invalid", "invalid"), result.fold(
                ignored -> List.<String>of(),
                errors -> errors.errors().stream().map(error -> error.code()).toList()));
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationCommand.from("invalid", "seven77", POLICY));
    }

    @Test
    void registrationValidatesBoundariesAndPreservesOriginalPasswordBytes() {
        assertTrue(RegistrationCommand.tryFrom("one@example.org", "x".repeat(8), POLICY).isSuccess());
        assertTrue(RegistrationCommand.tryFrom("one@example.org", "x".repeat(256), POLICY).isSuccess());
        assertTrue(RegistrationCommand.tryFrom("one@example.org", "🌍".repeat(128), POLICY).isSuccess());
        assertTrue(RegistrationCommand.tryFrom("one@example.org", "x".repeat(7), POLICY).isFailure());
        assertTrue(RegistrationCommand.tryFrom("one@example.org", "x".repeat(257), POLICY).isFailure());
        assertTrue(RegistrationCommand.tryFrom("one@example.org", "bad\uD800sequence", POLICY).isFailure());
        RegistrationCommand command = RegistrationCommand.from("  ONE@example.org  ", " space  ", POLICY);
        assertEquals("one@example.org", command.email().normalized());
        assertEquals(" space  ", command.password());
        assertFalse(command.toString().contains(command.password()));
    }

    @Test
    void loginAllowsShortLegacyInputsButRejectsMalformedAndOversizedCredentials() {
        Result<LoginCommand, ValidationErrors> shortPassword = LoginCommand.tryFrom(
                " ONE@EXAMPLE.ORG ", "x", POLICY);
        assertTrue(shortPassword.isSuccess());
        assertEquals("one@example.org", shortPassword.fold(
                command -> command.email().normalized(), ignored -> "unexpected"));
        assertTrue(LoginCommand.tryFrom("one@example.org", "", POLICY).isFailure());
        assertTrue(LoginCommand.tryFrom("one@example.org", "x".repeat(257), POLICY).isFailure());
        assertTrue(LoginCommand.tryFrom("one@example.org", "bad\uDC00sequence", POLICY).isFailure());
        assertTrue(LoginCommand.tryFrom(null, null, POLICY).isFailure());
    }
}

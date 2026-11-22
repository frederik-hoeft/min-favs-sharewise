package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;

/** Immutable validated registration input; never print the raw password. */
public final class RegistrationCommand {

    private final EmailAddress email;
    private final String password;

    private RegistrationCommand(EmailAddress email, String password) {
        this.email = email;
        this.password = password;
    }

    public static RegistrationCommand from(String email, String password, PasswordPolicy policy) {
        return tryFrom(email, password, policy).fold(
                command -> command,
                errors -> { throw new IllegalArgumentException("Invalid registration credentials"); });
    }

    /** Structured validation for inbound adapters; reuses the same rules as {@link #from}. */
    public static Result<RegistrationCommand, ValidationErrors> tryFrom(
            String email, String password, PasswordPolicy policy) {
        return CredentialCommandInput.parse(email, password, policy, true)
                .map(input -> new RegistrationCommand(input.email(), input.password()));
    }

    public EmailAddress email() {
        return email;
    }

    public String password() {
        return password;
    }

    @Override
    public String toString() {
        return "RegistrationCommand[credentials redacted]";
    }
}

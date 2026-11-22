package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;

/** Immutable validated login input; length limits apply even to legacy credentials. */
public final class LoginCommand {

    private final EmailAddress email;
    private final String password;

    private LoginCommand(EmailAddress email, String password) {
        this.email = email;
        this.password = password;
    }

    public static LoginCommand from(String email, String password, PasswordPolicy policy) {
        return tryFrom(email, password, policy).fold(
                command -> command,
                errors -> { throw new IllegalArgumentException("Invalid login credentials"); });
    }

    /** Structured validation for inbound adapters; reuses the same rules as {@link #from}. */
    public static Result<LoginCommand, ValidationErrors> tryFrom(
            String email, String password, PasswordPolicy policy) {
        return CredentialCommandInput.parse(email, password, policy, false)
                .map(input -> new LoginCommand(input.email(), input.password()));
    }

    public EmailAddress email() {
        return email;
    }

    public String password() {
        return password;
    }

    @Override
    public String toString() {
        return "LoginCommand[credentials redacted]";
    }
}

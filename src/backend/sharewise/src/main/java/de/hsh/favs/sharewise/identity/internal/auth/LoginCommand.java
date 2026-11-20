package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;

import java.util.Objects;

/** Immutable validated login input; length limits apply even to legacy credentials. */
public final class LoginCommand {

    private final EmailAddress email;
    private final String password;

    private LoginCommand(EmailAddress email, String password) {
        this.email = email;
        this.password = password;
    }

    public static LoginCommand from(String email, String password, PasswordPolicy policy) {
        Objects.requireNonNull(policy, "policy").validateVerificationInput(password);
        return new LoginCommand(EmailAddress.parse(email), password);
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

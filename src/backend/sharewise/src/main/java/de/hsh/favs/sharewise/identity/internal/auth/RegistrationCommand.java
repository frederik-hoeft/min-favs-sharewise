package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;

import java.util.Objects;

/** Immutable validated registration input; never print the raw password. */
public final class RegistrationCommand {

    private final EmailAddress email;
    private final String password;

    private RegistrationCommand(EmailAddress email, String password) {
        this.email = email;
        this.password = password;
    }

    public static RegistrationCommand from(String email, String password, PasswordPolicy policy) {
        Objects.requireNonNull(policy, "policy").validateRegistration(password);
        return new RegistrationCommand(EmailAddress.parse(email), password);
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

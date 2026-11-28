package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.identity.internal.profile.DisplayName;
import de.hsh.favs.sharewise.shared.validation.ValidationError;
import java.util.ArrayList;
import java.util.List;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;

/** Immutable validated registration input; never print the raw password. */
public final class RegistrationCommand {

    private final EmailAddress email;
    private final String password;
    private final DisplayName displayName;

    private RegistrationCommand(EmailAddress email, String password, DisplayName displayName) {
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    public static RegistrationCommand from(String email, String password, String displayName, PasswordPolicy policy) {
        return tryFrom(email, password, displayName, policy).fold(
                command -> command,
                errors -> { throw new IllegalArgumentException("Invalid registration credentials"); });
    }

    /** Structured validation for inbound adapters; reuses the same rules as {@link #from}. */
    public static Result<RegistrationCommand, ValidationErrors> tryFrom(
            String email, String password, String displayName, PasswordPolicy policy) {
        Result<CredentialCommandInput, ValidationErrors> credentials = CredentialCommandInput.parse(email, password, policy, true);
        Result<DisplayName, ValidationErrors> name = DisplayName.tryFrom(displayName);
        List<ValidationError> errors = new ArrayList<>();
        if (credentials instanceof Result.Err<CredentialCommandInput, ValidationErrors> invalid) errors.addAll(invalid.error().errors());
        if (name instanceof Result.Err<DisplayName, ValidationErrors> invalid) errors.addAll(invalid.error().errors());
        if (!errors.isEmpty()) return Result.err(new ValidationErrors(errors));
        return credentials.flatMap(input -> name.map(valid -> new RegistrationCommand(input.email(), input.password(), valid)));
    }

    public EmailAddress email() {
        return email;
    }

    public DisplayName displayName() { return displayName; }

    public String password() {
        return password;
    }

    @Override
    public String toString() {
        return "RegistrationCommand[credentials redacted]";
    }
}

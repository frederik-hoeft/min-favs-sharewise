package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationError;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One validation path for all IAM credential commands, including non-HTTP callers. */
record CredentialCommandInput(EmailAddress email, String password) {

    static Result<CredentialCommandInput, ValidationErrors> parse(
            String email, String password, PasswordPolicy policy, boolean registration) {
        Objects.requireNonNull(policy, "policy");
        List<ValidationError> errors = new ArrayList<>(2);
        EmailAddress parsedEmail = null;
        try {
            parsedEmail = EmailAddress.parse(email);
        } catch (IllegalArgumentException exception) {
            errors.add(new ValidationError("email", "invalid", "Enter a valid email address."));
        }
        try {
            if (registration) {
                policy.validateRegistration(password);
            } else {
                policy.validateVerificationInput(password);
            }
        } catch (IllegalArgumentException exception) {
            errors.add(new ValidationError("password", "invalid", "Enter a valid password."));
        }
        if (!errors.isEmpty()) {
            return Result.err(new ValidationErrors(errors));
        }
        return Result.ok(new CredentialCommandInput(parsedEmail, password));
    }

    @Override
    public String toString() {
        return "CredentialCommandInput[credentials redacted]";
    }
}

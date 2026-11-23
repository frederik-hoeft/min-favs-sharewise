package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.Objects;

/** Validated password replacement input; raw credentials are never represented in logs. */
public final class ChangePasswordCommand {

    private final String currentPassword;
    private final String newPassword;

    private ChangePasswordCommand(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public static Result<ChangePasswordCommand, ValidationErrors> tryFrom(
            String currentPassword, String newPassword, PasswordPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        try {
            policy.validateVerificationInput(currentPassword);
        } catch (IllegalArgumentException invalid) {
            return Result.err(ValidationErrors.single("currentPassword", "invalid", "Invalid current password."));
        }
        try {
            policy.validateRegistration(newPassword);
        } catch (IllegalArgumentException invalid) {
            return Result.err(ValidationErrors.single("newPassword", "invalid", "Invalid new password."));
        }
        if (currentPassword.equals(newPassword)) {
            return Result.err(ValidationErrors.single("newPassword", "unchanged",
                    "New password must differ from the current password."));
        }
        return Result.ok(new ChangePasswordCommand(currentPassword, newPassword));
    }

    public String currentPassword() {
        return currentPassword;
    }

    public String newPassword() {
        return newPassword;
    }

    @Override
    public String toString() {
        return "ChangePasswordCommand[credentials redacted]";
    }
}

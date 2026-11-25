package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.auth.ChangePasswordCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.ChangePasswordRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adapts the HTTP request to the independently validated password-change command. */
@Component
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class PasswordChangeCommandFactory
        implements DomainObjectFactory<ChangePasswordCommand, ChangePasswordRequest> {

    private final PasswordPolicy policy;

    PasswordChangeCommandFactory(PasswordPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public Result<ChangePasswordCommand, ValidationErrors> createFrom(ChangePasswordRequest input) {
        if (input == null) {
            return Result.err(ValidationErrors.single("request", "required", "Request body is required."));
        }
        return ChangePasswordCommand.tryFrom(input.currentPassword(), input.newPassword(), policy);
    }
}

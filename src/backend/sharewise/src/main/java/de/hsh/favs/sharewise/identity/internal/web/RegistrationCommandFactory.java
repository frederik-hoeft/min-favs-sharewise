package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.auth.RegistrationCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.RegistrationRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** HTTP DTO adapter; domain validation remains in IAM command construction. */
@Component
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class RegistrationCommandFactory
        implements DomainObjectFactory<RegistrationCommand, RegistrationRequest> {

    private final PasswordPolicy policy;

    RegistrationCommandFactory(PasswordPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public Result<RegistrationCommand, ValidationErrors> createFrom(RegistrationRequest input) {
        if (input == null) {
            return Result.err(ValidationErrors.single("request", "required", "Credentials are required."));
        }
        return RegistrationCommand.tryFrom(input.email(), input.password(), input.displayName(), policy);
    }
}

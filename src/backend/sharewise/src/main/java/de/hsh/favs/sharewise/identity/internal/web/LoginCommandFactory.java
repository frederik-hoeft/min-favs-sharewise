package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.auth.LoginCommand;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.AuthCredentialsRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** HTTP DTO adapter; domain validation remains in IAM command construction. */
@Component
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class LoginCommandFactory
        implements DomainObjectFactory<LoginCommand, AuthCredentialsRequest> {

    private final PasswordPolicy policy;

    LoginCommandFactory(PasswordPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public Result<LoginCommand, ValidationErrors> createFrom(AuthCredentialsRequest input) {
        if (input == null) {
            return Result.err(ValidationErrors.single("request", "required", "Credentials are required."));
        }
        return LoginCommand.tryFrom(input.email(), input.password(), policy);
    }
}

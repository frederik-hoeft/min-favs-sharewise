package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.profile.DisplayName;
import de.hsh.favs.sharewise.identity.internal.web.dto.AccountProfileUpdateRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Maps the HTTP profile DTO to a domain-validated display-name assignment. */
@Component
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class AccountProfileUpdateFactory
        implements DomainObjectFactory<DisplayName, AccountProfileUpdateRequest> {

    @Override
    public Result<DisplayName, ValidationErrors> createFrom(AccountProfileUpdateRequest input) {
        if (input == null) {
            return Result.err(ValidationErrors.single("request", "required", "Profile update is required."));
        }
        return DisplayName.tryFrom(input.displayName());
    }
}

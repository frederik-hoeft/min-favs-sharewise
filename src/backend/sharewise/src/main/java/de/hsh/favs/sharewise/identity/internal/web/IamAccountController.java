package de.hsh.favs.sharewise.identity.internal.web;

import tools.jackson.databind.JsonNode;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfile;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileFailure;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileService;
import de.hsh.favs.sharewise.identity.internal.profile.DisplayNameUpdate;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bearer-only IAM account projection and optional display-name update. */
@RestController
@ApiAccess(ApiAccess.Mode.AUTHENTICATED_IAM)
@RequestMapping("/api/iam/v1/account")
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class IamAccountController {

    private final OperationIngressDispatcher ingress;

    IamAccountController(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @GetMapping
    ResponseEntity<?> get() {
        Result<AccountProfile, AccountProfileFailure> result = ingress.dispatch(
                AccountProfileService.class, AccountProfileService::get);
        return response(result);
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> update(@RequestBody JsonNode input) {
        DisplayNameUpdate command = parse(input);
        Result<AccountProfile, AccountProfileFailure> result = ingress.dispatch(
                AccountProfileService.class, service -> service.setDisplayName(command));
        return response(result);
    }

    private static DisplayNameUpdate parse(JsonNode input) {
        if (input == null || !input.isObject() || !input.has("displayName")
                || input.size() != 1 || !(input.get("displayName").isTextual()
                    || input.get("displayName").isNull())) {
            throw RequestValidationException.ofSingleField("displayName", "invalid",
                    "Expected exactly one displayName field containing a string or null.");
        }
        String value = input.get("displayName").isNull() ? null : input.get("displayName").textValue();
        return DisplayNameUpdate.tryFrom(value).fold(
                command -> command, errors -> { throw RequestValidationException.from(errors); });
    }

    private static ResponseEntity<?> response(Result<AccountProfile, AccountProfileFailure> result) {
        return result.fold(
                profile -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile),
                denied -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .cacheControl(CacheControl.noStore())
                        .body(ApiProblems.of(HttpStatus.FORBIDDEN, "account_unavailable",
                                "Account Unavailable", "This account is unavailable.")));
    }
}

package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.auth.ChangePasswordCommand;
import de.hsh.favs.sharewise.identity.internal.auth.PasswordChangeFailure;
import de.hsh.favs.sharewise.identity.internal.auth.PasswordChangeService;
import de.hsh.favs.sharewise.identity.internal.auth.PasswordChangeStatus;
import de.hsh.favs.sharewise.identity.internal.web.dto.ChangePasswordRequest;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.DomainObjectFactory;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bearer-only password command, separate from cookie-backed credential entry. */
@RestController
@ApiAccess(ApiAccess.Mode.AUTHENTICATED_IAM)
@RequestMapping("/api/iam/v1/auth/password")
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class IamPasswordController {

    private final OperationIngressDispatcher ingress;
    private final DomainObjectFactory<ChangePasswordCommand, ChangePasswordRequest> commands;

    IamPasswordController(OperationIngressDispatcher ingress,
            @Qualifier("passwordChangeCommandFactory")
            DomainObjectFactory<ChangePasswordCommand, ChangePasswordRequest> commands) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> change(@RequestBody ChangePasswordRequest input) {
        ChangePasswordCommand command = commands.createFrom(input).fold(
                validated -> validated,
                errors -> { throw RequestValidationException.from(errors); });
        Result<PasswordChangeStatus, PasswordChangeFailure> result = ingress.dispatch(
                PasswordChangeService.class, service -> service.change(command));
        return result.fold(
                ignored -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(),
                IamPasswordController::denied);
    }

    private static ResponseEntity<ProblemDetail> denied(PasswordChangeFailure failure) {
        HttpStatus status = switch (failure) {
            case CURRENT_PASSWORD_INVALID -> HttpStatus.BAD_REQUEST;
            case CREDENTIAL_CHANGED -> HttpStatus.CONFLICT;
            case ACCOUNT_UNAVAILABLE -> HttpStatus.FORBIDDEN;
            case SESSION_UNAVAILABLE -> HttpStatus.UNAUTHORIZED;
        };
        String code = switch (failure) {
            case CURRENT_PASSWORD_INVALID -> "current_password_invalid";
            case CREDENTIAL_CHANGED -> "credential_changed";
            case ACCOUNT_UNAVAILABLE -> "account_unavailable";
            case SESSION_UNAVAILABLE -> "session_unavailable";
        };
        String title = switch (failure) {
            case CURRENT_PASSWORD_INVALID -> "Current Password Invalid";
            case CREDENTIAL_CHANGED -> "Credentials Changed";
            case ACCOUNT_UNAVAILABLE -> "Account Unavailable";
            case SESSION_UNAVAILABLE -> "Session Unavailable";
        };
        String detail = switch (failure) {
            case CURRENT_PASSWORD_INVALID -> "The current password is invalid or the account is temporarily locked.";
            case CREDENTIAL_CHANGED -> "The account credentials changed. Sign in and try again.";
            case ACCOUNT_UNAVAILABLE -> "This account is unavailable.";
            case SESSION_UNAVAILABLE -> "This session is unavailable. Sign in again to continue.";
        };
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblems.of(status, code, title, detail));
    }
}

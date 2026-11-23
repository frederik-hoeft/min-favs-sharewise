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
        ProblemDetail problem = switch (failure) {
            case CURRENT_PASSWORD_INVALID -> ApiProblems.of(
                    HttpStatus.BAD_REQUEST, "current_password_invalid", "Current Password Invalid",
                    "The current password is invalid or the account is temporarily locked.");
            case CREDENTIAL_CHANGED -> ApiProblems.of(
                    HttpStatus.CONFLICT, "credential_changed", "Credentials Changed",
                    "The account credentials changed. Sign in and try again.");
            case ACCOUNT_UNAVAILABLE -> ApiProblems.of(
                    HttpStatus.FORBIDDEN, "account_unavailable", "Account Unavailable",
                    "This account is unavailable.");
            case SESSION_UNAVAILABLE -> ApiProblems.of(
                    HttpStatus.UNAUTHORIZED, "session_unavailable", "Session Unavailable",
                    "This session is unavailable. Sign in again to continue.");
        };
        return ResponseEntity.status(problem.getStatus())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}

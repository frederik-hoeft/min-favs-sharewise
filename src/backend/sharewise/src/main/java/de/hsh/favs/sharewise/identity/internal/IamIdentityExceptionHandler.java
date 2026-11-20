package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps a missing required IAM principal to authentication, not business authorization, failure. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class IamIdentityExceptionHandler {

    @ExceptionHandler(UnauthenticatedCallerException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(UnauthenticatedCallerException ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblems.of(HttpStatus.UNAUTHORIZED, "unauthenticated",
                "Authentication Required", "An authenticated IAM caller is required."));
    }
}

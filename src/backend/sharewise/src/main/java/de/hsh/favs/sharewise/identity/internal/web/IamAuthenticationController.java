package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.auth.AuthenticationResult;
import de.hsh.favs.sharewise.identity.internal.auth.LoginCommand;
import de.hsh.favs.sharewise.identity.internal.auth.LoginFailure;
import de.hsh.favs.sharewise.identity.internal.auth.LoginService;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationCommand;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationFailure;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationService;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.FieldErrorDetail;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous credential entry through the trusted HTTP-to-operation-scope ingress. */
@RestController
@RequestMapping("/api/iam/v1")
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class IamAuthenticationController {

    private static final String REFRESH_COOKIE = "__Host-sharewise-refresh";

    private final OperationIngressDispatcher ingress;
    private final PasswordPolicy passwords;
    private final CsrfTokenRepository csrfTokens;
    private final Clock clock;

    IamAuthenticationController(OperationIngressDispatcher ingress, PasswordPolicy passwords,
            CsrfTokenRepository csrfTokens, @Qualifier("iamClock") Clock clock) {
        this.ingress = Objects.requireNonNull(ingress);
        this.passwords = Objects.requireNonNull(passwords);
        this.csrfTokens = Objects.requireNonNull(csrfTokens);
        this.clock = Objects.requireNonNull(clock);
    }

    @GetMapping("/csrf")
    CsrfBootstrap csrf(HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (!(attribute instanceof CsrfToken token)) {
            throw new IllegalStateException("Spring Security CSRF token was not initialized");
        }
        // With XorCsrfTokenRequestAttributeHandler this is masked for the response,
        // whereas the HttpOnly cookie retains the repository's unmasked secret.
        return new CsrfBootstrap(token.getToken());
    }

    @PostMapping(value = "/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> register(@RequestBody Credentials input, HttpServletRequest request,
            HttpServletResponse response) {
        RegistrationCommand command = registration(input);
        Result<AuthenticationResult, RegistrationFailure> result = ingress.dispatch(
                RegistrationService.class, service -> service.register(command));
        return result.fold(
                success -> succeeded(success, HttpStatus.CREATED, request, response),
                ignored -> ApiProblems.toResponse(ApiProblems.of(HttpStatus.CONFLICT,
                        "email_unavailable", "Email Unavailable", "This email address is unavailable.")));
    }

    @PostMapping(value = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> login(@RequestBody Credentials input, HttpServletRequest request,
            HttpServletResponse response) {
        LoginCommand command = loginCommand(input);
        Result<AuthenticationResult, LoginFailure> result = ingress.dispatch(
                LoginService.class, service -> service.login(command));
        return result.fold(
                success -> succeeded(success, HttpStatus.OK, request, response),
                ignored -> ApiProblems.toResponse(ApiProblems.of(HttpStatus.UNAUTHORIZED,
                        "invalid_credentials", "Authentication Failed", "Invalid credentials.")));
    }

    private RegistrationCommand registration(Credentials input) {
        checkInput(input, true);
        return RegistrationCommand.from(input.email(), input.password(), passwords);
    }

    private LoginCommand loginCommand(Credentials input) {
        checkInput(input, false);
        return LoginCommand.from(input.email(), input.password(), passwords);
    }

    private void checkInput(Credentials input, boolean registration) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (input == null) {
            throw RequestValidationException.ofSingleField("request", "required", "Credentials are required.");
        }
        try {
            EmailAddress.parse(input.email());
        } catch (IllegalArgumentException exception) {
            errors.add(new FieldErrorDetail("email", "invalid", "Enter a valid email address."));
        }
        try {
            if (registration) {
                passwords.validateRegistration(input.password());
            } else {
                passwords.validateVerificationInput(input.password());
            }
        } catch (IllegalArgumentException exception) {
            errors.add(new FieldErrorDetail("password", "invalid", "Enter a valid password."));
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }

    private ResponseEntity<AuthResponse> succeeded(AuthenticationResult result, HttpStatus status,
            HttpServletRequest request, HttpServletResponse response) {
        // This is reached only when Transactions.execute has returned after confirmed commit.
        Instant now = clock.instant();
        long maxAge = Math.max(0, Duration.between(now, result.refreshExpiresAt()).getSeconds());
        if (maxAge == 0) {
            throw new IllegalStateException("Committed IAM session has expired before cookie issuance");
        }
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, result.refreshCredential().cookieValue())
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(Duration.ofSeconds(maxAge)).build();
        // Prevent a token pair obtained before authentication from being reused later.
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(result.accessToken().value(), "Bearer",
                        result.accessToken().expiresAt()));
    }

    record Credentials(String email, String password) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    record AuthResponse(String accessToken, String tokenType, Instant expiresAt) {
        @Override
        public String toString() {
            return "AuthResponse[redacted]";
        }
    }

    record CsrfBootstrap(String token) {
        @Override
        public String toString() {
            return "CsrfBootstrap[redacted]";
        }
    }
}

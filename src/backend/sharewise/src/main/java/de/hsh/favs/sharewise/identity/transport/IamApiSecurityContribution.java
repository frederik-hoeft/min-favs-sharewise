package de.hsh.favs.sharewise.identity.transport;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Host integration hook for IAM's primary HTTP credential admission and auth-route
 * CSRF/Origin protections. The application owns matching and filter-chain order.
 * A contributor configures the supplied chain but must not build or register one.
 */
public interface IamApiSecurityContribution {

    void contribute(HttpSecurity api);

    /** Admission can only use the verified, request-local primary IAM evidence. */
    boolean hasVerifiedIdentity(HttpServletRequest request);
}

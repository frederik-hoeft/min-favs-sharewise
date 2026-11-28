package de.hsh.favs.sharewise.shared.infra.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/** Host integration for supplementary credential admission without a domain routing switch. */
public interface SupplementaryApiSecurityContribution {
    void contribute(HttpSecurity api, Predicate<HttpServletRequest> hasVerifiedPrimary);
}

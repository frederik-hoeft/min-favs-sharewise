package de.hsh.favs.sharewise.identity.internal.verification;

import java.util.Optional;
import java.util.UUID;

/**
 * IAM-private read projection for a matching, unrevoked session belonging to an enabled account.
 * Temporal eligibility is evaluated by the application service after the read. Login lockout is
 * not part of session eligibility. Adapters throw {@link EligibilityQueryUnavailableException}
 * only for recognized infrastructure outages; unexpected SQL/programming failures propagate.
 */
public interface IdentityEligibilityQuery {
    Optional<SessionValidityWindow> findSessionValidityWindow(UUID userId, UUID sessionId);
}

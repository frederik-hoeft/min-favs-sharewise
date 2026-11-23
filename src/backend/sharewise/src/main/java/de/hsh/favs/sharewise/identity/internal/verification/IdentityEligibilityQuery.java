package de.hsh.favs.sharewise.identity.internal.verification;

import java.time.Instant;
import java.util.UUID;

/**
 * IAM-private read projection. The database adapter checks account enablement, session ownership,
 * creation, revocation, and both exclusive expiration deadlines in one query at {@code now}.
 * Login lockout is not part of session eligibility. Adapters throw
 * {@link EligibilityQueryUnavailableException} only for recognized infrastructure outages;
 * unexpected SQL/programming failures propagate.
 */
public interface IdentityEligibilityQuery {
    boolean isEligible(UUID userId, UUID sessionId, Instant now);
}

package de.hsh.favs.sharewise.identity.internal.verification;

import java.util.Objects;

/**
 * A persistence adapter has classified an infrastructure failure as preventing a reliable
 * eligibility read. Other database errors (including invalid SQL) must not be reclassified.
 */
public final class EligibilityQueryUnavailableException extends RuntimeException {

    public EligibilityQueryUnavailableException(Throwable cause) {
        super("IAM eligibility lookup unavailable", Objects.requireNonNull(cause, "cause"));
    }
}

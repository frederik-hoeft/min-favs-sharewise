package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Session lifetime policy: a fixed absolute deadline, sliding idle deadline, and access
 * tokens whose expiration cannot exceed either session deadline.
 *
 * @param accessLifetime maximum access-JWT lifetime
 * @param idleLifetime sliding inactivity allowance
 * @param absoluteLifetime maximum duration of a session from creation
 */
public record SessionPolicy(Duration accessLifetime, Duration idleLifetime, Duration absoluteLifetime) {

    public SessionPolicy {
        requirePositive(accessLifetime);
        requirePositive(idleLifetime);
        requirePositive(absoluteLifetime);
    }

    private static void requirePositive(Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Session lifetimes must be positive");
        }
    }

    /**
     * @return the default five-minute access, seven-day idle, and thirty-day absolute policy
     */
    public static SessionPolicy initial() {
        return new SessionPolicy(Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofDays(30));
    }

    /**
     * Creates the initial idle and absolute deadlines for a new session.
     *
     * @param now authoritative session-creation time
     * @return initial deadlines capped by the absolute lifetime
     */
    public Deadlines startAt(Instant now) {
        Objects.requireNonNull(now, "now");
        Instant absolute = now.plus(absoluteLifetime);
        Instant idle = earliest(now.plus(idleLifetime), absolute);
        return new Deadlines(now, idle, absolute);
    }

    /**
     * Renews idle eligibility without moving the original absolute deadline or shortening
     * an existing idle deadline.
     *
     * @param now authoritative refresh time
     * @param current current persisted session deadlines
     * @return deadlines with a renewed, absolute-capped idle expiration
     * @throws IllegalStateException if the session has already expired
     */
    public Deadlines renewAt(Instant now, Deadlines current) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(current, "current");
        if (!now.isBefore(current.effectiveExpiresAt())) {
            throw new IllegalStateException("Session expired");
        }
        Instant proposed = earliest(now.plus(idleLifetime), current.absoluteExpiresAt());
        Instant nextIdle = proposed.isAfter(current.idleExpiresAt()) ? proposed : current.idleExpiresAt();
        return new Deadlines(current.createdAt(), nextIdle, current.absoluteExpiresAt());
    }

    /**
     * Computes the expiration of an access JWT issued for the given session.
     *
     * @param now authoritative issuance time
     * @param deadlines current session eligibility deadlines
     * @return the earliest of access lifetime, idle expiration, and absolute expiration
     * @throws IllegalStateException if the session has already expired
     */
    public Instant accessExpiresAt(Instant now, Deadlines deadlines) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(deadlines, "deadlines");
        if (!now.isBefore(deadlines.effectiveExpiresAt())) {
            throw new IllegalStateException("Session expired");
        }
        return earliest(now.plus(accessLifetime), deadlines.effectiveExpiresAt());
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    /**
     * Immutable session-creation, sliding idle, and fixed absolute deadlines.
     *
     * @param createdAt authoritative original session-creation time
     * @param idleExpiresAt current sliding idle deadline
     * @param absoluteExpiresAt fixed upper bound for this session
     */
    public record Deadlines(Instant createdAt, Instant idleExpiresAt, Instant absoluteExpiresAt) {
        public Deadlines {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
            Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
            if (!absoluteExpiresAt.isAfter(createdAt) || idleExpiresAt.isAfter(absoluteExpiresAt)
                    || !idleExpiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("Invalid session deadlines");
            }
        }

        /**
         * @return the earlier of idle and absolute expiration
         */
        public Instant effectiveExpiresAt() {
            return earliest(idleExpiresAt, absoluteExpiresAt);
        }

        /**
         * Computes a cookie maximum age without extending past either session deadline.
         *
         * @param now authoritative time from which the cookie duration is measured
         * @return remaining whole seconds, rounded down and never negative
         */
        public long cookieMaxAgeSeconds(Instant now) {
            Objects.requireNonNull(now, "now");
            long seconds = Duration.between(now, effectiveExpiresAt()).getSeconds();
            return Math.max(0L, seconds);
        }
    }
}

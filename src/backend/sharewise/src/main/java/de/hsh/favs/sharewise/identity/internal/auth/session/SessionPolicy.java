package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed absolute deadline, initial idle deadline, and bounded access-token lifetime. */
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

    public static SessionPolicy initial() {
        return new SessionPolicy(Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofDays(30));
    }

    public Deadlines startAt(Instant now) {
        Objects.requireNonNull(now, "now");
        Instant absolute = now.plus(absoluteLifetime);
        Instant idle = earliest(now.plus(idleLifetime), absolute);
        return new Deadlines(now, idle, absolute);
    }

    /** Renew idle eligibility without ever extending the absolute deadline or shortening idle time. */
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

        public Instant effectiveExpiresAt() {
            return earliest(idleExpiresAt, absoluteExpiresAt);
        }

        /** Full seconds, rounded down; never extends a cookie past either deadline. */
        public long cookieMaxAgeSeconds(Instant now) {
            Objects.requireNonNull(now, "now");
            long seconds = Duration.between(now, effectiveExpiresAt()).getSeconds();
            return Math.max(0L, seconds);
        }
    }
}

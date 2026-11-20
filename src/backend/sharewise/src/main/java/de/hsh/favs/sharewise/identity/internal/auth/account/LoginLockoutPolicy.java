package de.hsh.favs.sharewise.identity.internal.auth.account;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Pure login-failure arithmetic; the persistence adapter serializes account updates. */
public record LoginLockoutPolicy(int threshold, Duration duration) {

    public LoginLockoutPolicy {
        Objects.requireNonNull(duration, "duration");
        if (threshold < 1 || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Invalid lockout policy");
        }
    }

    public static LoginLockoutPolicy initial() {
        return new LoginLockoutPolicy(5, Duration.ofMinutes(15));
    }

    public boolean isLocked(State state, Instant now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        return state.lockedUntil() != null && now.isBefore(state.lockedUntil());
    }

    public State failure(State state, Instant now) {
        if (isLocked(state, now)) {
            return state;
        }
        // An expired lock starts a new counter window, not one more increment.
        int previous = state.lockedUntil() != null ? 0 : state.failedAttempts();
        int attempts = previous >= threshold ? threshold : previous + 1;
        Instant lockedUntil = attempts == threshold ? now.plus(duration) : null;
        return new State(attempts, lockedUntil);
    }

    public State success() {
        return new State(0, null);
    }

    public record State(int failedAttempts, Instant lockedUntil) {
        public State {
            if (failedAttempts < 0) {
                throw new IllegalArgumentException("Negative login failure count");
            }
        }
    }
}

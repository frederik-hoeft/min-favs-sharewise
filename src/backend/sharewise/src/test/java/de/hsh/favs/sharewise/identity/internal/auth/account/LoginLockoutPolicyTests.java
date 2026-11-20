package de.hsh.favs.sharewise.identity.internal.auth.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginLockoutPolicyTests {

    private final LoginLockoutPolicy policy = LoginLockoutPolicy.initial();
    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void fifthFailureLocksForFifteenMinutesAndActiveLockDoesNotChange() {
        LoginLockoutPolicy.State state = policy.success();
        for (int i = 1; i < 5; i++) {
            state = policy.failure(state, now);
            assertEquals(i, state.failedAttempts());
            assertFalse(policy.isLocked(state, now));
        }
        LoginLockoutPolicy.State locked = policy.failure(state, now);
        assertEquals(5, locked.failedAttempts());
        assertEquals(now.plus(Duration.ofMinutes(15)), locked.lockedUntil());
        assertTrue(policy.isLocked(locked, now.plus(Duration.ofMinutes(14))));
        assertEquals(locked, policy.failure(locked, now.plusSeconds(14)));
        assertFalse(policy.isLocked(locked, locked.lockedUntil()));
    }

    @Test
    void elapsedLockStartsFreshWindowAndSuccessfulLoginResetsState() {
        LoginLockoutPolicy.State locked = new LoginLockoutPolicy.State(5, now);
        LoginLockoutPolicy.State next = policy.failure(locked, now);
        assertEquals(new LoginLockoutPolicy.State(1, null), next);
        assertEquals(new LoginLockoutPolicy.State(0, null), policy.success());
        assertEquals(new LoginLockoutPolicy.State(0, null), policy.success());
    }

    @Test
    void rejectsInvalidStateOrPolicyAndDoesNotOverflowCounters() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoginLockoutPolicy.State(-1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LoginLockoutPolicy(0, Duration.ofMinutes(15)));
        assertThrows(IllegalArgumentException.class,
                () -> new LoginLockoutPolicy(5, Duration.ZERO));
        LoginLockoutPolicy.State corruptCounter = new LoginLockoutPolicy.State(Integer.MAX_VALUE, null);
        assertEquals(5, policy.failure(corruptCounter, now).failedAttempts());
    }
}

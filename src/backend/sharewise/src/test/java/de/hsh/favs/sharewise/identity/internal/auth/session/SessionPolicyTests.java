package de.hsh.favs.sharewise.identity.internal.auth.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionPolicyTests {

    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void initialSessionHasFixedAbsoluteAndCappedIdleExpiration() {
        SessionPolicy policy = SessionPolicy.initial();
        SessionPolicy.Deadlines deadlines = policy.startAt(now);
        assertEquals(now.plus(Duration.ofDays(7)), deadlines.idleExpiresAt());
        assertEquals(now.plus(Duration.ofDays(30)), deadlines.absoluteExpiresAt());
        assertEquals(now.plus(Duration.ofMinutes(5)), policy.accessExpiresAt(now, deadlines));
        assertEquals(Duration.ofDays(7).toSeconds(), deadlines.cookieMaxAgeSeconds(now));
        assertEquals(0, deadlines.cookieMaxAgeSeconds(now.plus(Duration.ofDays(8))));
    }

    @Test
    void shortIdleOrAbsoluteLifetimeCapsCookieAndAccessToken() {
        SessionPolicy policy = new SessionPolicy(
                Duration.ofMinutes(5), Duration.ofDays(30), Duration.ofSeconds(30));
        SessionPolicy.Deadlines deadlines = policy.startAt(now);
        assertEquals(deadlines.absoluteExpiresAt(), deadlines.idleExpiresAt());
        assertEquals(now.plusSeconds(30), policy.accessExpiresAt(now, deadlines));
        assertEquals(29, deadlines.cookieMaxAgeSeconds(now.plusMillis(1)));
        assertThrows(IllegalStateException.class,
                () -> policy.accessExpiresAt(now.plusSeconds(30), deadlines));
    }

    @Test
    void invalidConfigurationsAndDeadlinesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionPolicy(Duration.ZERO, Duration.ofDays(1), Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionPolicy(Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionPolicy.Deadlines(now, now.plusSeconds(2), now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionPolicy.Deadlines(now, now, now.plusSeconds(2)));
    }
}

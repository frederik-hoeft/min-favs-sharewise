package de.hsh.favs.sharewise.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract checks without introducing a test authentication bean into production. */
class IdentityContextTests {

    @Test
    void authenticatedCallerProvidesConsistentAccessorsToConsumer() {
        UUID userId = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be137");
        UUID sessionId = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be138");
        IdentityContext context = new IdentityContext() {
            @Override public Optional<UUID> userId() { return Optional.of(userId); }
            @Override public Optional<UUID> sessionId() { return Optional.of(sessionId); }
        };
        GroupLikeConsumer consumer = new GroupLikeConsumer(context);

        assertEquals(Optional.of(userId), context.userId());
        assertEquals(Optional.of(sessionId), context.sessionId());
        assertEquals(sessionId, context.requireSessionId());
        assertTrue(context.isAuthenticated());
        assertEquals(userId, context.requireUserId());
        assertEquals(userId, consumer.currentUserId());
    }

    @Test
    void anonymousCallerIsDistinctFromAuthorizationDenial() {
        IdentityContext context = new IdentityContext() {
            @Override public Optional<UUID> userId() { return Optional.empty(); }
            @Override public Optional<UUID> sessionId() { return Optional.empty(); }
        };

        assertEquals(Optional.empty(), context.userId());
        assertFalse(context.isAuthenticated());
        assertEquals(Optional.empty(), context.sessionId());
        assertThrows(UnauthenticatedCallerException.class, context::requireSessionId);
        assertThrows(UnauthenticatedCallerException.class, context::requireUserId);
    }

    @Test
    void providerFailureIsNotConvertedToAnonymousCaller() {
        IllegalStateException failure = new IllegalStateException("Invalid credential");
        IdentityContext context = new IdentityContext() {
            @Override public Optional<UUID> userId() { throw failure; }
            @Override public Optional<UUID> sessionId() { throw failure; }
        };

        assertSame(failure, assertThrows(IllegalStateException.class, context::userId));
        assertSame(failure, assertThrows(IllegalStateException.class, context::requireUserId));
        assertSame(failure, assertThrows(IllegalStateException.class, context::requireSessionId));
        assertSame(failure, assertThrows(IllegalStateException.class, context::isAuthenticated));
    }

    /** Compile-time consumer proof; actual Spring fixture wiring is Phase B. */
    private record GroupLikeConsumer(IdentityContext identity) {

        UUID currentUserId() {
            return identity.requireUserId();
        }
    }
}

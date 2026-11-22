package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * IAM-private persistence contract for one refresh/logout transaction segment.
 *
 * <p>Look up a selector's session without locking, then lock that session row and re-read
 * the presented generation. Refresh and logout always acquire the session lock before
 * updating token history, idle deadlines or revocation state. Physical serialization,
 * conditional updates and row-count verification are the Phase B adapter's responsibility.
 */
public interface SessionLifecycleStore {

    /** Selector ownership is untrusted until the secret is verified after locking. */
    Optional<UUID> findSessionId(UUID tokenId);

    /** Lock one session for mutation, and obtain the account's current disabled status. */
    Optional<LockedSession> lockSession(UUID sessionId);

    /** Re-read the verifier and consumption state after acquiring the session lock. */
    Optional<RefreshTokenRecord> findToken(UUID tokenId);

    /** Consume exactly the currently active generation; fail rather than ignore a stale update. */
    void consume(UUID tokenId, Instant consumedAt);

    /** Extend only the locked session's idle deadline, without moving its absolute deadline. */
    void extendIdle(UUID sessionId, Instant idleExpiresAt);

    /** Persist family revocation; repeated revocation is idempotent. */
    void revoke(UUID sessionId, Instant revokedAt);

    record LockedSession(LoginSession session, boolean userDisabled) {
        public LockedSession {
            Objects.requireNonNull(session, "session");
        }
    }
}

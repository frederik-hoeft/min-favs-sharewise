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

    /**
     * Resolves a token selector to its owning session without acquiring a row lock.
     * The selector alone does not establish authorization; the caller must later verify
     * the secret after locking and re-reading the token generation.
     *
     * @param tokenId untrusted token selector from the presented bearer
     * @return owning session ID, or empty when the selector does not exist
     */
    Optional<UUID> findSessionId(UUID tokenId);

    /**
     * Acquires the session row lock for the current transaction segment, then reads the
     * durable session and account-disabled status used for the lifecycle decision.
     *
     * @param sessionId session owning the presented token generation
     * @return locked session and account status, or empty when it no longer exists
     */
    Optional<LockedSession> lockSession(UUID sessionId);

    /**
     * Re-reads a token generation after its owning session row has been locked.
     * The result, including {@code consumedAt}, must reflect the serialized state.
     *
     * @param tokenId presented token selector
     * @return the latest persisted generation, or empty if absent
     */
    Optional<RefreshTokenRecord> findToken(UUID tokenId);

    /**
     * Consumes exactly one currently active generation in the locked session.
     * A missing or already-consumed row is an invariant violation, never a silent no-op.
     *
     * @param tokenId active refresh generation to consume
     * @param consumedAt authoritative time of consumption
     * @throws IllegalStateException if exactly one active generation cannot be consumed
     */
    void consume(UUID tokenId, Instant consumedAt);

    /**
     * Persists a policy-computed idle deadline for the locked session, preserving the
     * absolute deadline. An absent session must not be ignored.
     *
     * @param sessionId locked session to update
     * @param idleExpiresAt new idle deadline, bounded by the absolute deadline
     * @throws IllegalStateException if the locked session cannot be updated
     */
    void extendIdle(UUID sessionId, Instant idleExpiresAt);

    /**
     * Revokes the entire refresh family through its owning session row.
     * Repeated revocation is idempotent; token history is retained for replay detection.
     *
     * @param sessionId locked session to revoke
     * @param revokedAt authoritative revocation time
     */
    void revoke(UUID sessionId, Instant revokedAt);

    /**
     * Session and owning account state observed while holding the session row lock.
     *
     * @param session durable session state
     * @param userDisabled whether the owning account is disabled
     */
    record LockedSession(LoginSession session, boolean userDisabled) {
        public LockedSession {
            Objects.requireNonNull(session, "session");
        }
    }
}

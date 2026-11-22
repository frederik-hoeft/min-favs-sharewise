package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.PresentedRefreshCredential;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * IAM refresh rotation and logout. Only verified, consumed credentials revoke a family;
 * an ordinary denial abstains from mutation. The result leaves the transaction boundary
 * only after its physical completion, so transport cannot release uncommitted credentials.
 */
public final class SessionLifecycleService {

    private final Transactions transactions;
    private final SessionLifecycleStore sessions;
    private final RefreshTokenStore refreshTokens;
    private final RefreshCredentialGenerator credentials;
    private final SessionPolicy policy;
    private final AccessTokenIssuer accessTokens;
    private final Clock clock;

    /**
     * Creates the application service for refresh rotation and logout.
     *
     * @param transactions executes each lifecycle decision in a single transaction segment
     * @param sessions persistence operations serialized by the owning session row
     * @param refreshTokens allocates and stores replacement refresh generations
     * @param credentials generates high-entropy refresh bearer credentials
     * @param policy bounds idle, absolute, and access-token lifetimes
     * @param accessTokens issues the replacement access JWT inside the transaction segment
     * @param clock authoritative server-side time source
     */
    public SessionLifecycleService(Transactions transactions, SessionLifecycleStore sessions,
            RefreshTokenStore refreshTokens, RefreshCredentialGenerator credentials,
            SessionPolicy policy, AccessTokenIssuer accessTokens, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validates and rotates a refresh generation under the owning session lock.
     * Correct-secret reuse of a consumed generation revokes the session family; an active
     * generation with a mismatched expected session is rejected without mutation.
     * Credentials are returned only after transaction completion.
     *
     * @param cookieValue opaque refresh bearer presented by the browser; may be absent or malformed
     * @param expectedSessionId tab-local session binding, or {@code null} only for initial restoration
     * @return new credentials on success, or a transport-neutral refresh failure
     */
    public Result<AuthenticationResult, SessionLifecycleFailure> refresh(
            String cookieValue, UUID expectedSessionId) {
        Optional<PresentedRefreshCredential> presented = PresentedRefreshCredential.parse(cookieValue);
        if (presented.isEmpty()) {
            return Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE);
        }
        return transactions.execute(() -> {
            Optional<VerifiedSession> verified = verifyUnderSessionLock(presented.orElseThrow());
            if (verified.isEmpty()) {
                return unavailable();
            }
            VerifiedSession match = verified.orElseThrow();
            Instant now = clock.instant();
            if (match.token().consumedAt() != null) {
                sessions.revoke(match.state().session().id(), now);
                return TransactionOutcome.commit(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE));
            }
            LoginSession session = match.state().session();
            if (match.state().userDisabled() || session.revokedAt() != null
                    || !now.isBefore(session.deadlines().effectiveExpiresAt())
                    || now.isBefore(match.token().issuedAt())) {
                return unavailable();
            }
            if (expectedSessionId != null && !expectedSessionId.equals(session.id())) {
                return TransactionOutcome.abstain(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED));
            }

            SessionPolicy.Deadlines nextDeadlines = policy.renewAt(now, session.deadlines());
            // All three writes and signing execute in one segment; errors roll back everything.
            sessions.consume(match.token().id(), now);
            sessions.extendIdle(session.id(), nextDeadlines.idleExpiresAt());
            UUID successorId = refreshTokens.newTokenId();
            RefreshCredentialGenerator.GeneratedCredential successor = credentials.generate(successorId);
            refreshTokens.insert(new RefreshTokenRecord(successorId, session.id(),
                    successor.verifierHash(), now, null));
            Instant expiresAt = policy.accessExpiresAt(now, nextDeadlines);
            IssuedAccessToken access = accessTokens.issue(session.userId(), session.id(), now, expiresAt);
            if (!expiresAt.equals(access.expiresAt())) {
                throw new IllegalStateException("Access token issuer returned the wrong expiration");
            }
            return TransactionOutcome.commit(Result.ok(new AuthenticationResult(
                    session.userId(), session.id(), access, successor, nextDeadlines.effectiveExpiresAt())));
        });
    }

    /**
     * Revokes the session named by a verified refresh credential.
     * Absent or invalid credentials produce an idempotent no-session result; a verified
     * active credential with a different expected session must not revoke that session.
     * Verified reuse of a consumed generation still revokes its family.
     *
     * @param cookieValue opaque refresh bearer presented by the browser; may be absent or malformed
     * @param expectedSessionId tab-local session binding, or {@code null} for an unbound caller
     * @return the logout outcome or a session-context mismatch that must not clear the cookie
     */
    public Result<LogoutStatus, SessionLifecycleFailure> logout(String cookieValue, UUID expectedSessionId) {
        Optional<PresentedRefreshCredential> presented = PresentedRefreshCredential.parse(cookieValue);
        if (presented.isEmpty()) {
            return Result.ok(LogoutStatus.NO_SESSION);
        }
        return transactions.execute(() -> {
            Optional<VerifiedSession> verified = verifyUnderSessionLock(presented.orElseThrow());
            if (verified.isEmpty()) {
                return TransactionOutcome.abstain(Result.ok(LogoutStatus.NO_SESSION));
            }
            VerifiedSession match = verified.orElseThrow();
            Instant now = clock.instant();
            LoginSession session = match.state().session();
            if (match.token().consumedAt() != null) {
                // Correct-secret replay takes precedence over a caller-supplied session binding.
                sessions.revoke(session.id(), now);
                return TransactionOutcome.commit(Result.ok(LogoutStatus.REVOKED));
            }
            if (expectedSessionId != null && !expectedSessionId.equals(session.id())) {
                return TransactionOutcome.abstain(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED));
            }
            sessions.revoke(session.id(), now);
            return TransactionOutcome.commit(Result.ok(LogoutStatus.REVOKED));
        });
    }

    private Optional<VerifiedSession> verifyUnderSessionLock(PresentedRefreshCredential presented) {
        Optional<UUID> owner = sessions.findSessionId(presented.tokenId());
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        UUID sessionId = owner.orElseThrow();
        Optional<SessionLifecycleStore.LockedSession> state = sessions.lockSession(sessionId);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        SessionLifecycleStore.LockedSession locked = state.orElseThrow();
        if (!sessionId.equals(locked.session().id())) {
            throw new IllegalStateException("IAM lifecycle store locked an inconsistent session");
        }
        Optional<RefreshTokenRecord> token = sessions.findToken(presented.tokenId());
        if (token.isEmpty()) {
            return Optional.empty();
        }
        RefreshTokenRecord record = token.orElseThrow();
        if (!sessionId.equals(record.sessionId()) || !presented.matches(record)) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedSession(locked, record));
    }

    private static TransactionOutcome<Result<AuthenticationResult, SessionLifecycleFailure>> unavailable() {
        return TransactionOutcome.abstain(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE));
    }

    private record VerifiedSession(SessionLifecycleStore.LockedSession state, RefreshTokenRecord token) { }
}

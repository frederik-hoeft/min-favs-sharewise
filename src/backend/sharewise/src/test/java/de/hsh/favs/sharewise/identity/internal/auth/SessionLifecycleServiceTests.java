package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenParser;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Application contract tests; SQL lock ordering and physical COMMIT remain Phase B E2E gates. */
class SessionLifecycleServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final UUID USER = UUID.fromString("019ab800-0000-7000-8000-000000000021");
    private static final UUID SESSION = UUID.fromString("019ab800-0000-7000-8000-000000000022");
    private static final UUID OTHER = UUID.fromString("019ab800-0000-7000-8000-000000000023");

    @Test
    void rotationConsumesExactlyOneGenerationAndKeepsSessionIdentityAndAbsoluteLimit() {
        Rig rig = new Rig();
        AuthenticationResult result = success(rig.service.refresh(rig.cookie, SESSION));

        assertEquals(USER, result.userId());
        assertEquals(SESSION, result.sessionId());
        assertEquals(NOW.plusSeconds(300), result.accessToken().expiresAt());
        assertEquals(NOW.plus(Duration.ofDays(7)), result.refreshExpiresAt());
        assertEquals(rig.original.selector(), rig.store.tokens.get(rig.original.selector()).id());
        assertEquals(NOW, rig.store.tokens.get(rig.original.selector()).consumedAt());
        assertEquals(2, rig.store.tokens.size());
        assertEquals(1, rig.store.tokens.values().stream().filter(token -> token.consumedAt() == null).count());
        assertEquals(SESSION, rig.store.tokens.get(result.refreshCredential().selector()).sessionId());
        assertEquals(result.refreshExpiresAt(), rig.store.session.deadlines().idleExpiresAt());
        assertEquals(rig.absolute, rig.store.session.deadlines().absoluteExpiresAt());
        assertEquals(1, rig.transactions.commits);
        assertEquals(1, rig.issuer.issued);
        assertFalse(result.toString().contains(result.refreshCredential().value()));
    }

    @Test
    void unboundRestorationAndNextGenerationRenewalRemainWithinSameSession() {
        Rig rig = new Rig();
        AuthenticationResult first = success(rig.service.refresh(rig.cookie, null));
        AuthenticationResult second = success(rig.service.refresh(first.refreshCredential().value(), SESSION));
        assertEquals(SESSION, second.sessionId());
        assertNotEquals(first.refreshCredential().selector(), second.refreshCredential().selector());
        assertEquals(3, rig.store.tokens.size());
        assertEquals(1, rig.store.tokens.values().stream().filter(token -> token.consumedAt() == null).count());
    }

    @Test
    void invalidSelectorsAndIncorrectSecretsNeverRevokeEvenAConsumedGeneration() {
        Rig rig = new Rig();
        String wrongSecret = rig.generators.generate(rig.original.selector()).value();
        String unknownId = rig.generators.generate(OTHER).value();
        for (String cookie : new String[] {null, "", "invalid", "not-a-uuid.secret", wrongSecret, unknownId}) {
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    rig.service.refresh(cookie, SESSION));
            assertEquals(Result.ok(LogoutStatus.NO_SESSION), rig.service.logout(cookie, SESSION));
        }
        AuthenticationResult rotated = success(rig.service.refresh(rig.cookie, SESSION));
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                rig.service.refresh(wrongSecret, SESSION));
        assertNull(rig.store.session.revokedAt());
        assertEquals(2, rig.store.tokens.size());
        assertEquals(1, rig.issuer.issued);
        assertTrue(rig.store.tokens.containsKey(rotated.refreshCredential().selector()));
    }

    @Test
    void authenticatedConsumedTokenRevokesFamilyEvenWithMismatchedExpectation() {
        Rig rig = new Rig();
        AuthenticationResult rotated = success(rig.service.refresh(rig.cookie, SESSION));
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                rig.service.refresh(rig.cookie, OTHER));
        assertEquals(NOW, rig.store.session.revokedAt());
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                rig.service.refresh(rotated.refreshCredential().value(), SESSION));
        assertEquals(2, rig.store.tokens.size());
        assertEquals(2, rig.transactions.commits);
    }

    @Test
    void verifiedLiveContextMismatchHasNoMutationForEitherOperation() {
        Rig rig = new Rig();
        assertEquals(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED),
                rig.service.refresh(rig.cookie, OTHER));
        assertEquals(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED),
                rig.service.logout(rig.cookie, OTHER));
        assertEquals(0, rig.transactions.commits);
        assertNull(rig.store.session.revokedAt());
        assertNull(rig.store.tokens.get(rig.original.selector()).consumedAt());
        assertEquals(1, rig.store.tokens.size());
        assertEquals(0, rig.issuer.issued);
    }

    @Test
    void idleAndAbsoluteDeadlinesAreExclusiveAndDoNotAllowRenewalAtBoundary() {
        Rig idle = new Rig();
        idle.store.setIdle(NOW);
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                idle.service.refresh(idle.cookie, SESSION));
        assertNull(idle.store.tokens.get(idle.original.selector()).consumedAt());

        Rig absolute = new Rig();
        absolute.store.setDeadlines(new SessionPolicy.Deadlines(
                NOW.minus(Duration.ofDays(30)), NOW, NOW));
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                absolute.service.refresh(absolute.cookie, SESSION));
        assertEquals(0, absolute.issuer.issued);
    }

    @Test
    void rejectsRefreshBeforeConsumptionWhenLessThanTwoSecondsRemain() {
        for (Duration remaining : new Duration[] {Duration.ofMillis(900), Duration.ofMillis(1999)}) {
            Rig rig = new Rig();
            Instant deadline = NOW.plus(remaining);
            rig.store.setDeadlines(new SessionPolicy.Deadlines(NOW.minus(Duration.ofDays(1)),
                    deadline, deadline));
            assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                    rig.service.refresh(rig.cookie, SESSION));
            assertNull(rig.store.tokens.get(rig.original.selector()).consumedAt());
            assertEquals(1, rig.store.tokens.size());
            assertEquals(0, rig.issuer.issued);
            assertEquals(0, rig.transactions.commits);
        }

        Rig boundary = new Rig();
        Instant deadline = NOW.plusSeconds(2);
        boundary.store.setDeadlines(new SessionPolicy.Deadlines(NOW.minus(Duration.ofDays(1)),
                deadline, deadline));
        AuthenticationResult result = success(boundary.service.refresh(boundary.cookie, SESSION));
        assertEquals(deadline, result.refreshExpiresAt());
        assertEquals(1, boundary.transactions.commits);
    }

    @Test
    void refreshCanExtendNearExpiredIdleDeadlineWhenAbsoluteExpiryIsDistant() {
        for (Duration remaining : new Duration[] {Duration.ofMillis(500), Duration.ofMillis(1999)}) {
            Rig rig = new Rig();
            SessionPolicy.Deadlines current = rig.store.session.deadlines();
            rig.store.setDeadlines(new SessionPolicy.Deadlines(current.createdAt(),
                    NOW.plus(remaining), current.absoluteExpiresAt()));

            AuthenticationResult result = success(rig.service.refresh(rig.cookie, SESSION));
            assertEquals(NOW.plus(Duration.ofDays(7)), result.refreshExpiresAt());
            assertEquals(result.refreshExpiresAt(), rig.store.session.deadlines().idleExpiresAt());
            assertEquals(current.absoluteExpiresAt(), rig.store.session.deadlines().absoluteExpiresAt());
            assertEquals(NOW, rig.store.tokens.get(rig.original.selector()).consumedAt());
            assertEquals(2, rig.store.tokens.size());
            assertEquals(1, rig.transactions.commits);
        }
    }

    @Test
    void disabledRevokedAndFutureIssuedTokensDoNotRenew() {
        Rig disabled = new Rig();
        disabled.store.disabled = true;
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                disabled.service.refresh(disabled.cookie, SESSION));
        assertNull(disabled.store.session.revokedAt());

        Rig revoked = new Rig();
        revoked.store.session = new LoginSession(SESSION, USER,
                revoked.store.session.deadlines(), NOW.minusSeconds(20));
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                revoked.service.refresh(revoked.cookie, SESSION));

        Rig future = new Rig();
        RefreshTokenRecord token = future.store.tokens.get(future.original.selector());
        future.store.tokens.put(token.id(), new RefreshTokenRecord(token.id(), SESSION,
                token.verifierHash(), NOW.plusSeconds(1), null));
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                future.service.refresh(future.cookie, SESSION));
        assertEquals(0, future.issuer.issued);
    }

    @Test
    void renewalCapsNewTokenAndCookieAtAbsoluteDeadlineAndNeverShortensIdle() {
        Rig rig = new Rig(new SessionPolicy(Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofDays(30)));
        rig.store.setDeadlines(new SessionPolicy.Deadlines(NOW.minus(Duration.ofDays(30)).plusSeconds(20),
                NOW.plusSeconds(20), NOW.plusSeconds(20)));
        AuthenticationResult result = success(rig.service.refresh(rig.cookie, SESSION));
        assertEquals(NOW.plusSeconds(20), result.accessToken().expiresAt());
        assertEquals(NOW.plusSeconds(20), result.refreshExpiresAt());
        assertEquals(NOW.plusSeconds(20), rig.store.session.deadlines().idleExpiresAt());
        assertEquals(20, rig.store.session.deadlines().cookieMaxAgeSeconds(NOW));

        Rig backward = new Rig();
        backward.store.setIdle(NOW.plus(Duration.ofDays(8)));
        success(backward.service.refresh(backward.cookie, SESSION));
        assertEquals(NOW.plus(Duration.ofDays(8)), backward.store.session.deadlines().idleExpiresAt());
    }

    @Test
    void logoutIsIdempotentAndRevokesOnlyTheVerifiedSession() {
        Rig rig = new Rig();
        assertEquals(Result.ok(LogoutStatus.REVOKED), rig.service.logout(rig.cookie, SESSION));
        assertEquals(NOW, rig.store.session.revokedAt());
        assertEquals(Result.ok(LogoutStatus.REVOKED), rig.service.logout(rig.cookie, SESSION));
        assertEquals(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE),
                rig.service.refresh(rig.cookie, SESSION));
        assertEquals(0, rig.issuer.issued);
        assertEquals(2, rig.transactions.commits);
    }

    @Test
    void logoutOfCorrectlyVerifiedConsumedGenerationAlsoDetectsReplay() {
        Rig rig = new Rig();
        success(rig.service.refresh(rig.cookie, SESSION));
        assertEquals(Result.ok(LogoutStatus.REVOKED), rig.service.logout(rig.cookie, OTHER));
        assertEquals(NOW, rig.store.session.revokedAt());
    }

    @Test
    void insertionAndSigningFailuresRollbackConsumptionAndIdleExtension() {
        Rig insert = new Rig();
        insert.store.failInsert = true;
        assertThrows(IllegalStateException.class, () -> insert.service.refresh(insert.cookie, SESSION));
        insert.assertUnchanged();

        Rig signer = new Rig();
        signer.issuer.fail = true;
        assertThrows(IllegalStateException.class, () -> signer.service.refresh(signer.cookie, SESSION));
        signer.assertUnchanged();
    }

    @Test
    void failedCommitDoesNotExposeCredentialsOrPersistGeneratedSuccessor() {
        Rig rig = new Rig();
        rig.transactions.failCommit = true;
        assertThrows(IllegalStateException.class, () -> rig.service.refresh(rig.cookie, SESSION));
        rig.assertUnchanged();
        assertEquals(0, rig.transactions.commits);
        assertEquals(1, rig.issuer.issued);
    }

    @Test
    void failedCommitRollsBackLogoutRevocation() {
        Rig rig = new Rig();
        rig.transactions.failCommit = true;
        assertThrows(IllegalStateException.class, () -> rig.service.logout(rig.cookie, SESSION));
        rig.assertUnchanged();
        assertEquals(0, rig.transactions.commits);
    }

    @Test
    void expiredOrDisabledSessionsCanBeSignedOutWithoutGrantingNewCredentials() {
        Rig rig = new Rig();
        rig.store.disabled = true;
        rig.store.setIdle(NOW);
        assertEquals(Result.ok(LogoutStatus.REVOKED), rig.service.logout(rig.cookie, SESSION));
        assertEquals(NOW, rig.store.session.revokedAt());
        assertEquals(0, rig.issuer.issued);
    }

    @Test
    void successfulMutationsHaveOneCommitAndDenialsAbstain() {
        Rig rig = new Rig();
        rig.service.refresh(rig.generators.generate(OTHER).value(), SESSION);
        rig.service.refresh(rig.cookie, OTHER);
        assertEquals(0, rig.transactions.commits);
        assertEquals(2, rig.transactions.rollbacks);
        success(rig.service.refresh(rig.cookie, SESSION));
        assertEquals(1, rig.transactions.commits);
    }

    private static AuthenticationResult success(Result<AuthenticationResult, SessionLifecycleFailure> outcome) {
        assertTrue(outcome.isSuccess());
        return ((Result.Ok<AuthenticationResult, SessionLifecycleFailure>) outcome).value();
    }

    private static final class Rig {
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final FakeStore store = new FakeStore();
        final ApplicationTokenGenerator generators = new ApplicationTokenGenerator();
        final FakeIssuer issuer = new FakeIssuer();
        final FakeTransactions transactions = new FakeTransactions(store);
        final SessionLifecycleService service;
        final ApplicationTokenGenerator.GeneratedToken original;
        final String cookie;
        final Instant absolute;

        Rig() { this(SessionPolicy.initial()); }

        Rig(SessionPolicy policy) {
            SessionPolicy.Deadlines deadlines = policy.startAt(NOW.minus(Duration.ofDays(1)));
            store.session = new LoginSession(SESSION, USER, deadlines, null);
            original = generators.generate(UUID.randomUUID());
            cookie = original.value();
            store.tokens.put(original.selector(), new RefreshTokenRecord(original.selector(), SESSION,
                    original.verifierHash(), NOW.minusSeconds(60), null));
            absolute = deadlines.absoluteExpiresAt();
            service = new SessionLifecycleService(transactions, store, store, generators, new ApplicationTokenParser(), policy, issuer, clock);
        }

        void assertUnchanged() {
            assertEquals(1, store.tokens.size());
            assertNull(store.tokens.get(original.selector()).consumedAt());
            assertEquals(absolute, store.session.deadlines().absoluteExpiresAt());
            assertEquals(absolute.minus(Duration.ofDays(23)), store.session.deadlines().idleExpiresAt());
            assertNull(store.session.revokedAt());
        }
    }

    private static final class FakeStore implements SessionLifecycleStore, RefreshTokenStore {
        LoginSession session;
        final Map<UUID, RefreshTokenRecord> tokens = new HashMap<>();
        boolean disabled;
        boolean locked;
        boolean failInsert;

        void setIdle(Instant idle) { setDeadlines(new SessionPolicy.Deadlines(
                session.deadlines().createdAt(), idle, session.deadlines().absoluteExpiresAt())); }
        void setDeadlines(SessionPolicy.Deadlines deadlines) {
            session = new LoginSession(session.id(), session.userId(), deadlines, session.revokedAt());
        }

        @Override public Optional<UUID> findSessionId(UUID tokenId) {
            RefreshTokenRecord record = tokens.get(tokenId);
            return record == null ? Optional.empty() : Optional.of(record.sessionId());
        }
        @Override public Optional<LockedSession> lockSession(UUID sessionId) {
            if (!session.id().equals(sessionId)) { return Optional.empty(); }
            locked = true;
            return Optional.of(new LockedSession(session, disabled));
        }
        @Override public Optional<RefreshTokenRecord> findToken(UUID tokenId) {
            requireLock();
            return Optional.ofNullable(tokens.get(tokenId));
        }
        @Override public void consume(UUID tokenId, Instant consumedAt) {
            requireLock();
            RefreshTokenRecord record = tokens.get(tokenId);
            if (record == null || record.consumedAt() != null || consumedAt.isBefore(record.issuedAt())) {
                throw new IllegalStateException("Stale refresh generation");
            }
            tokens.put(tokenId, new RefreshTokenRecord(tokenId, record.sessionId(), record.verifierHash(),
                    record.issuedAt(), consumedAt));
        }
        @Override public void extendIdle(UUID sessionId, Instant idleExpiresAt) {
            requireLock();
            if (!session.id().equals(sessionId)) { throw new IllegalStateException("Wrong session"); }
            setIdle(idleExpiresAt);
        }
        @Override public void revoke(UUID sessionId, Instant revokedAt) {
            requireLock();
            if (!session.id().equals(sessionId)) { throw new IllegalStateException("Wrong session"); }
            if (session.revokedAt() == null) {
                session = new LoginSession(session.id(), session.userId(), session.deadlines(), revokedAt);
            }
        }
        @Override public void revokeOtherSessions(UUID userId, UUID retainedSessionId, Instant revokedAt) {
            // This fixture models only one retained session; the Phase D unit/E2E suites
            // exercise cross-session revocation independently.
            requireLock();
        }
        @Override public UUID newTokenId() { requireLock(); return UUID.randomUUID(); }
        @Override public void insert(RefreshTokenRecord record) {
            requireLock();
            if (failInsert || tokens.values().stream().anyMatch(token ->
                    token.sessionId().equals(record.sessionId()) && token.consumedAt() == null)) {
                throw new IllegalStateException("Simulated refresh insert failure");
            }
            tokens.put(record.id(), record);
        }
        private void requireLock() {
            if (!locked) { throw new IllegalStateException("Missing session lock"); }
        }
    }

    private static final class FakeIssuer implements AccessTokenIssuer {
        int issued;
        boolean fail;
        @Override public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant now, Instant expiresAt) {
            issued++;
            if (fail) { throw new IllegalStateException("Simulated signing failure"); }
            return new IssuedAccessToken("token-" + issued, expiresAt);
        }
    }

    private static final class FakeTransactions implements Transactions {
        private final FakeStore store;
        int commits;
        int rollbacks;
        boolean failCommit;
        FakeTransactions(FakeStore store) { this.store = store; }
        @Override public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
            LoginSession before = store.session;
            Map<UUID, RefreshTokenRecord> tokensBefore = new HashMap<>(store.tokens);
            try {
                TransactionOutcome<T> outcome = operation.get();
                if (outcome.decision() == TransactionDecision.COMMIT) {
                    if (failCommit) { throw new IllegalStateException("Simulated commit failure"); }
                    commits++;
                } else {
                    restore(before, tokensBefore);
                    rollbacks++;
                }
                return outcome.result();
            } catch (RuntimeException | Error failure) {
                restore(before, tokensBefore);
                rollbacks++;
                throw failure;
            } finally {
                store.locked = false;
            }
        }
        private void restore(LoginSession session, Map<UUID, RefreshTokenRecord> tokens) {
            store.session = session;
            store.tokens.clear();
            store.tokens.putAll(tokens);
        }
        @Override public void markRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override public boolean isRollbackOnly() { return false; }
    }

}

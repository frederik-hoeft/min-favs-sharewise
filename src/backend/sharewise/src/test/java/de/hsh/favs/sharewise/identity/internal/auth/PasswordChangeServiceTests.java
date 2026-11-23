package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.ZoneId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Domain contract and transaction-outcome tests; real row-lock races require PostgreSQL E2E. */
class PasswordChangeServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final UUID USER = UUID.fromString("019ab800-0000-7000-8000-000000000101");
    private static final UUID CALLER = UUID.fromString("019ab800-0000-7000-8000-000000000102");
    private static final UUID OTHER = UUID.fromString("019ab800-0000-7000-8000-000000000103");
    private static final String OLD = "old-good-password";
    private static final String NEW = "new-good-password";

    @Test
    void successfulChangeKeepsCallerAndRevokesOtherSessionsWithVerifierAndLockoutReset() {
        Rig rig = new Rig();
        rig.accounts.state = new LoginLockoutPolicy.State(2, null);
        assertEquals(Result.ok(PasswordChangeStatus.CHANGED), rig.service.change(command(OLD, NEW)));
        assertEquals("encoded:" + NEW, rig.accounts.hash);
        assertEquals(new LoginLockoutPolicy.State(0, null), rig.accounts.state);
        assertNull(rig.sessions.sessions.get(CALLER).revokedAt());
        assertEquals(NOW, rig.sessions.sessions.get(OTHER).revokedAt());
        assertEquals(1, rig.transactions.commits);
        assertEquals(1, rig.sessions.revocations);
        assertFalse(rig.encoder.usedDuringLock);
    }

    @Test
    void wrongCurrentPasswordCommitsOnlyCounterAndLocksAtThreshold() {
        Rig rig = new Rig();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID),
                    rig.service.change(command("wrong-password", NEW)));
            assertEquals(attempt, rig.accounts.state.failedAttempts());
        }
        assertEquals(NOW.plusSeconds(900), rig.accounts.state.lockedUntil());
        assertEquals(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID),
                rig.service.change(command(OLD, NEW)));
        assertEquals("encoded:" + OLD, rig.accounts.hash);
        assertEquals(5, rig.encoder.matchesCount);
        assertEquals(0, rig.encoder.encodesCount);
        assertNull(rig.sessions.sessions.get(OTHER).revokedAt());
        assertEquals(5, rig.transactions.commits);
        assertEquals(0, rig.sessions.revocations);
    }

    @Test
    void invalidAndUnchangedNewPasswordsAreRejectedBeforePersistence() {
        assertTrue(ChangePasswordCommand.tryFrom(OLD, OLD, PasswordPolicy.initial()).isFailure());
        assertTrue(ChangePasswordCommand.tryFrom(OLD, "short", PasswordPolicy.initial()).isFailure());
        assertTrue(ChangePasswordCommand.tryFrom(null, NEW, PasswordPolicy.initial()).isFailure());
        assertTrue(ChangePasswordCommand.tryFrom(OLD, "abc\uD800defghi", PasswordPolicy.initial()).isFailure());
        assertTrue(ChangePasswordCommand.tryFrom(OLD, NEW, PasswordPolicy.initial()).isSuccess());
        assertFalse(command(OLD, NEW).toString().contains(OLD));
    }

    @Test
    void staleVerifierFromConcurrentUpdateAbstainsWithoutClobberingOrRevoking() {
        Rig rig = new Rig();
        rig.encoder.afterMatch = () -> rig.accounts.hash = "encoded:another-change";
        assertEquals(Result.err(PasswordChangeFailure.CREDENTIAL_CHANGED), rig.service.change(command(OLD, NEW)));
        assertEquals("encoded:another-change", rig.accounts.hash);
        assertNull(rig.sessions.sessions.get(OTHER).revokedAt());
        assertEquals(0, rig.transactions.commits);
    }

    @Test
    void disabledLockedMissingAndSessionDenialsNeverMutate() {
        Rig disabled = new Rig();
        disabled.accounts.disabled = NOW;
        assertEquals(Result.err(PasswordChangeFailure.ACCOUNT_UNAVAILABLE),
                disabled.service.change(command(OLD, NEW)));
        Rig missing = new Rig();
        missing.accounts.present = false;
        assertEquals(Result.err(PasswordChangeFailure.ACCOUNT_UNAVAILABLE),
                missing.service.change(command(OLD, NEW)));
        Rig locked = new Rig();
        locked.accounts.state = new LoginLockoutPolicy.State(5, NOW.plusSeconds(20));
        assertEquals(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID),
                locked.service.change(command(OLD, NEW)));
        assertEquals(0, disabled.encoder.matchesCount);
        assertEquals(0, disabled.encoder.encodesCount);
        assertEquals(0, locked.encoder.matchesCount);
        assertEquals(0, locked.encoder.encodesCount);
        assertEquals(0, missing.encoder.matchesCount);
        Rig wrongOwner = new Rig();
        wrongOwner.sessions.sessions.put(CALLER, new LoginSession(CALLER, UUID.randomUUID(),
                wrongOwner.sessions.sessions.get(CALLER).deadlines(), null));
        assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                wrongOwner.service.change(command(OLD, NEW)));
        Rig missingSession = new Rig();
        missingSession.sessions.sessions.remove(CALLER);
        assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                missingSession.service.change(command(OLD, NEW)));
        Rig expiredSession = new Rig();
        expiredSession.sessions.sessions.put(CALLER, new LoginSession(CALLER, USER,
                new SessionPolicy.Deadlines(NOW.minusSeconds(7200), NOW.minusSeconds(1), NOW.plusSeconds(1)), null));
        assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                expiredSession.service.change(command(OLD, NEW)));
        for (Rig rig : new Rig[] {disabled, missing, locked, wrongOwner, missingSession, expiredSession}) {
            assertEquals("encoded:" + OLD, rig.accounts.hash);
            assertNull(rig.sessions.sessions.get(OTHER).revokedAt());
            assertEquals(0, rig.sessions.revocations);
        }
    }

    @Test
    void accountStateChangedDuringVerificationIsStillRejectedUnderTheAccountLock() {
        Rig disabled = new Rig();
        disabled.encoder.afterMatch = () -> disabled.accounts.disabled = NOW;
        assertEquals(Result.err(PasswordChangeFailure.ACCOUNT_UNAVAILABLE),
                disabled.service.change(command(OLD, NEW)));
        assertEquals(1, disabled.encoder.matchesCount);
        assertEquals("encoded:" + OLD, disabled.accounts.hash);
        assertEquals(0, disabled.sessions.revocations);

        Rig locked = new Rig();
        locked.encoder.afterMatch = () -> locked.accounts.state =
                new LoginLockoutPolicy.State(5, NOW.plusSeconds(900));
        assertEquals(Result.err(PasswordChangeFailure.CURRENT_PASSWORD_INVALID),
                locked.service.change(command(OLD, NEW)));
        assertEquals(1, locked.encoder.matchesCount);
        assertEquals("encoded:" + OLD, locked.accounts.hash);
        assertEquals(0, locked.sessions.revocations);
    }

    @Test
    void sessionExpiringDuringLockWaitCannotAuthorizePasswordReplacement() {
        MutableClock clock = new MutableClock(NOW);
        Rig rig = new Rig(clock);
        rig.sessions.sessions.put(CALLER, new LoginSession(CALLER, USER,
                new SessionPolicy.Deadlines(NOW.minusSeconds(100), NOW.plusSeconds(1),
                        NOW.plusSeconds(3600)), null));
        // Model the wall clock advancing while another refresh/logout holds the row lock.
        // Before the fix the service captured this decision time before lockSession().
        rig.sessions.onSessionLockWait = () -> clock.set(NOW.plusSeconds(1));

        assertEquals(Result.err(PasswordChangeFailure.SESSION_UNAVAILABLE),
                rig.service.change(command(OLD, NEW)));
        assertEquals("encoded:" + OLD, rig.accounts.hash);
        assertNull(rig.sessions.sessions.get(OTHER).revokedAt());
        assertEquals(0, rig.sessions.revocations);
        assertEquals(0, rig.transactions.commits);
    }

    @Test
    void failureDuringRevocationOrCommitRollsBackVerifierAndCounters() {
        Rig failure = new Rig();
        failure.sessions.failRevoke = true;
        assertThrows(IllegalStateException.class, () -> failure.service.change(command(OLD, NEW)));
        assertEquals("encoded:" + OLD, failure.accounts.hash);
        assertNull(failure.sessions.sessions.get(OTHER).revokedAt());
        Rig commit = new Rig();
        commit.transactions.failCommit = true;
        assertThrows(IllegalStateException.class, () -> commit.service.change(command(OLD, NEW)));
        assertEquals("encoded:" + OLD, commit.accounts.hash);
        assertNull(commit.sessions.sessions.get(OTHER).revokedAt());
        assertEquals(0, commit.transactions.commits);
    }

    @Test
    void primaryIdentityCannotChooseAnotherAccountsSessions() {
        Rig rig = new Rig();
        rig.userId = UUID.randomUUID();
        assertEquals(Result.err(PasswordChangeFailure.ACCOUNT_UNAVAILABLE),
                rig.service.change(command(OLD, NEW)));
    }

    private static ChangePasswordCommand command(String oldPassword, String newPassword) {
        return ((Result.Ok<ChangePasswordCommand, ?>) ChangePasswordCommand.tryFrom(
                oldPassword, newPassword, PasswordPolicy.initial())).value();
    }

    private static final class Rig {
        final Accounts accounts = new Accounts();
        final Sessions sessions = new Sessions();
        final FakeTransactions transactions = new FakeTransactions(accounts, sessions);
        final Encoder encoder = new Encoder(accounts);
        UUID userId = USER;
        UUID sessionId = CALLER;
        final IdentityContext identity = new IdentityContext() {
            @Override public Optional<UUID> userId() { return Optional.of(Rig.this.userId); }
            @Override public Optional<UUID> sessionId() { return Optional.of(Rig.this.sessionId); }
        };
        final PasswordChangeService service;
        Rig() { this(Clock.fixed(NOW, ZoneOffset.UTC)); }
        Rig(Clock clock) {
            service = new PasswordChangeService(identity, transactions,
                    accounts, sessions, encoder, LoginLockoutPolicy.initial(), clock);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;
        MutableClock(Instant now) { this(new AtomicReference<>(now), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }
        void set(Instant now) { current.set(now); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(current, zone); }
    }

    private static final class Accounts implements AccountStore {
        String hash = "encoded:" + OLD;
        LoginLockoutPolicy.State state = new LoginLockoutPolicy.State(0, null);
        Instant disabled;
        boolean present = true;
        boolean locked;

        AccountSnapshot snapshot() {
            return new AccountSnapshot(USER, EmailAddress.parse("test@example.org"), hash,
                    NOW.minusSeconds(3600), disabled, state);
        }
        @Override public Optional<AccountSnapshot> findById(UUID userId) {
            return present && USER.equals(userId) ? Optional.of(snapshot()) : Optional.empty();
        }
        @Override public Optional<AccountSnapshot> findByIdForUpdate(UUID userId) {
            locked = true;
            return findById(userId);
        }
        @Override public void replacePassword(UUID userId, String previous, String replacement) {
            if (!locked || !USER.equals(userId) || !hash.equals(previous)) {
                throw new IllegalStateException("Invalid verifier update");
            }
            hash = replacement;
            state = new LoginLockoutPolicy.State(0, null);
        }
        @Override public boolean compareAndSetLoginFailures(UUID userId, LoginLockoutPolicy.State previous,
                LoginLockoutPolicy.State next) {
            if (!locked || !state.equals(previous)) { return false; }
            state = next;
            return true;
        }
        @Override public Optional<AccountSnapshot> findByEmail(EmailAddress email) { throw new AssertionError(); }
        @Override public Optional<AccountSnapshot> findByEmailForUpdate(EmailAddress email) {
            throw new AssertionError();
        }
        @Override public Optional<AccountSnapshot> insert(EmailAddress email, String hash) {
            throw new AssertionError();
        }
    }

    private static final class Sessions implements SessionLifecycleStore {
        final Map<UUID, LoginSession> sessions = new HashMap<>();
        int revocations;
        boolean failRevoke;
        Runnable onSessionLockWait;
        Sessions() {
            SessionPolicy.Deadlines dates = SessionPolicy.initial().startAt(NOW.minusSeconds(100));
            sessions.put(CALLER, new LoginSession(CALLER, USER, dates, null));
            sessions.put(OTHER, new LoginSession(OTHER, USER, dates, null));
        }
        @Override public Optional<LockedSession> lockSession(UUID sessionId) {
            if (onSessionLockWait != null) { onSessionLockWait.run(); }
            return Optional.ofNullable(sessions.get(sessionId)).map(s -> new LockedSession(s, false));
        }
        @Override public void revokeOtherSessions(UUID userId, UUID retainedSessionId, Instant at) {
            revocations++;
            if (failRevoke) { throw new IllegalStateException("Simulated session write failure"); }
            sessions.replaceAll((id, s) -> s.userId().equals(userId) && !id.equals(retainedSessionId)
                    && s.revokedAt() == null ? new LoginSession(id, s.userId(), s.deadlines(), at) : s);
        }
        @Override public Optional<UUID> findSessionId(UUID id) { throw new AssertionError(); }
        @Override public Optional<de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord>
                findToken(UUID id) { throw new AssertionError(); }
        @Override public void consume(UUID id, Instant at) { throw new AssertionError(); }
        @Override public void extendIdle(UUID id, Instant at) { throw new AssertionError(); }
        @Override public void revoke(UUID id, Instant at) { throw new AssertionError(); }
    }

    private static final class Encoder implements CredentialEncoder {
        final Accounts accounts;
        Runnable afterMatch;
        boolean usedDuringLock;
        int matchesCount;
        int encodesCount;
        Encoder(Accounts accounts) { this.accounts = accounts; }
        @Override public boolean matches(String value, String hash) {
            matchesCount++;
            usedDuringLock |= accounts.locked;
            boolean matched = ("encoded:" + value).equals(hash);
            if (afterMatch != null) { afterMatch.run(); }
            return matched;
        }
        @Override public String encode(String value) {
            encodesCount++;
            usedDuringLock |= accounts.locked;
            return "encoded:" + value;
        }
    }

    private static final class FakeTransactions implements Transactions {
        final Accounts accounts;
        final Sessions sessions;
        int commits;
        boolean failCommit;
        FakeTransactions(Accounts accounts, Sessions sessions) {
            this.accounts = accounts;
            this.sessions = sessions;
        }
        @Override public <T> T execute(Supplier<TransactionOutcome<T>> work) {
            String oldHash = accounts.hash;
            LoginLockoutPolicy.State oldState = accounts.state;
            Map<UUID, LoginSession> saved = new HashMap<>(sessions.sessions);
            int previousRevocations = sessions.revocations;
            try {
                TransactionOutcome<T> result = work.get();
                if (result.decision() == TransactionDecision.COMMIT) {
                    if (failCommit) { throw new IllegalStateException("Commit failed"); }
                    commits++;
                    return result.result();
                }
                restore(oldHash, oldState, saved, previousRevocations);
                return result.result();
            } catch (RuntimeException | Error failure) {
                restore(oldHash, oldState, saved, previousRevocations);
                throw failure;
            } finally {
                accounts.locked = false;
            }
        }
        private void restore(String hash, LoginLockoutPolicy.State state,
                Map<UUID, LoginSession> saved, int previousRevocations) {
            accounts.hash = hash;
            accounts.state = state;
            sessions.sessions.clear();
            sessions.sessions.putAll(saved);
            sessions.revocations = previousRevocations;
        }
        @Override public void markRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override public boolean isRollbackOnly() { return false; }
    }
}

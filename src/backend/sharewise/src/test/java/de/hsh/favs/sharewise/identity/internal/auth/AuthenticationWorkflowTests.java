package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountSnapshot;
import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.EmailAddress;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Domain workflow tests using explicitly constructed fakes; no production credential wiring. */
class AuthenticationWorkflowTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final PasswordPolicy PASSWORDS = PasswordPolicy.initial();
    private static final String CORRECT = "long-enough-password";

    @Test
    void registrationCommitsAccountSessionAndOneVerifierBeforeReleasingCredentials() {
        Rig rig = new Rig();
        Result<AuthenticationResult, RegistrationFailure> result = rig.register.register(registration());
        assertTrue(result.isSuccess());
        AuthenticationResult authentication = ((Result.Ok<AuthenticationResult, RegistrationFailure>) result).value();
        assertEquals(1, rig.transactions.commits);
        assertEquals(1, rig.accounts.created);
        assertEquals(1, rig.sessions.created.size());
        assertEquals(1, rig.tokens.created.size());
        assertEquals(authentication.userId(), rig.accounts.userId);
        assertEquals(authentication.sessionId(), rig.sessions.created.getFirst().id());
        assertEquals(authentication.sessionId(), rig.tokens.created.getFirst().sessionId());
        assertEquals(authentication.refreshCredential().selector(), rig.tokens.created.getFirst().id());
        assertEquals(32, rig.tokens.created.getFirst().verifierHash().length);
        assertEquals(NOW.plusSeconds(300), authentication.accessToken().expiresAt());
        assertTrue(authentication.refreshCredential().value().startsWith(
                authentication.refreshCredential().selector().toString() + "."));
        assertFalse(authentication.toString().contains(authentication.refreshCredential().value()));
        assertEquals(List.of(authentication.userId()), rig.events.registered);
        assertEquals(List.of(NOW), rig.events.registeredAt);
    }

    @Test
    void duplicateRegistrationDoesNotCreateAnyMoreStateOrIssueTokens() {
        Rig rig = new Rig();
        rig.register.register(registration());
        Result<AuthenticationResult, RegistrationFailure> duplicate = rig.register.register(
                RegistrationCommand.from("  USER@example.org  ", CORRECT, PASSWORDS));
        assertEquals(Result.err(RegistrationFailure.EMAIL_UNAVAILABLE), duplicate);
        assertEquals(1, rig.sessions.created.size());
        assertEquals(1, rig.tokens.created.size());
        assertEquals(1, rig.issuer.issued);
        assertEquals(1, rig.transactions.commits);
        assertEquals(1, rig.transactions.rollbacks);
        assertEquals(1, rig.events.registered.size(), "an unavailable email publishes no registration");
    }

    @Test
    void correctLoginUsesTwoTransactionsResetsFailuresAndCreatesFreshSession() {
        Rig rig = new Rig();
        AuthenticationResult first = success(rig.register.register(registration()));
        rig.accounts.failureState = new LoginLockoutPolicy.State(3, null);
        AuthenticationResult second = success(rig.login.login(login(CORRECT)));
        assertNotEquals(first.sessionId(), second.sessionId());
        assertEquals(new LoginLockoutPolicy.State(0, null), rig.accounts.failureState);
        assertEquals(2, rig.transactions.commits);
        assertEquals(1, rig.transactions.rollbacks); // snapshot-only transaction
        assertEquals(2, rig.sessions.created.size());
        assertEquals(2, rig.tokens.created.size());
    }

    @Test
    void wrongPasswordCommitsFailureAndLocksOnFifthAttempt() {
        Rig rig = new Rig();
        rig.register.register(registration());
        for (int i = 1; i <= 5; i++) {
            assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login("wrong-password")));
            assertEquals(i, rig.accounts.failureState.failedAttempts());
        }
        assertEquals(NOW.plusSeconds(900), rig.accounts.failureState.lockedUntil());
        assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login(CORRECT)));
        assertEquals(1, rig.sessions.created.size());
        assertEquals(1, rig.tokens.created.size());
        assertEquals(6, rig.transactions.commits); // registration and five denied attempts
        assertEquals(7, rig.transactions.rollbacks); // six snapshots + locked denial
    }

    @Test
    void expiredLockCanBeClearedBySuccessfulLogin() {
        Rig rig = new Rig();
        rig.register.register(registration());
        rig.accounts.failureState = new LoginLockoutPolicy.State(5, NOW.minusSeconds(1));
        assertTrue(rig.login.login(login(CORRECT)).isSuccess());
        assertEquals(new LoginLockoutPolicy.State(0, null), rig.accounts.failureState);
    }

    @Test
    void unknownUserRunsDummyVerificationButNeverCreatesAccountOrSession() {
        Rig rig = new Rig();
        assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login(CORRECT)));
        assertEquals("dummy-verifier", rig.encoder.lastVerified);
        assertEquals(1, rig.encoder.matchesCalls);
        assertEquals(0, rig.accounts.created);
        assertTrue(rig.sessions.created.isEmpty());
        assertEquals(0, rig.issuer.issued);
    }

    @Test
    void disabledAndLockedAccountsAreDeniedEvenForCorrectPassword() {
        Rig rig = new Rig();
        rig.register.register(registration());
        rig.accounts.disabledAt = NOW.minusSeconds(10);
        assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login(CORRECT)));
        rig.accounts.disabledAt = null;
        rig.accounts.failureState = new LoginLockoutPolicy.State(5, NOW.plusSeconds(5));
        assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login(CORRECT)));
        assertEquals(1, rig.sessions.created.size());
    }

    @Test
    void changedPasswordOrReplacedAccountBetweenVerificationAndLockIsDenied() {
        Rig rig = new Rig();
        rig.register.register(registration());
        rig.encoder.onMatches = () -> rig.accounts.passwordHash = "encoded:changed-after-snapshot";
        assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login(CORRECT)));
        assertEquals(1, rig.sessions.created.size());
        rig.encoder.onMatches = () -> rig.accounts.userId = UUID.randomUUID();
        assertEquals(Result.err(LoginFailure.INVALID_CREDENTIALS), rig.login.login(login(CORRECT)));
        assertEquals(1, rig.sessions.created.size());
    }

    @Test
    void issuerFailureOrInsertFailureRollsBackWholeRegistration() {
        Rig signing = new Rig();
        signing.issuer.fail = true;
        assertThrows(IllegalStateException.class, () -> signing.register.register(registration()));
        assertTrue(signing.events.registered.isEmpty(), "failure before the account is complete publishes nothing");
        assertEquals(0, signing.accounts.created);
        assertTrue(signing.sessions.created.isEmpty());
        assertTrue(signing.tokens.created.isEmpty());
        assertEquals(0, signing.transactions.commits);

        Rig storing = new Rig();
        storing.tokens.fail = true;
        assertThrows(IllegalStateException.class, () -> storing.register.register(registration()));
        assertEquals(0, storing.accounts.created);
        assertTrue(storing.sessions.created.isEmpty());
    }

    @Test
    void failedCommitNeverReleasesCredentialsOrLeavesCommittedState() {
        Rig rig = new Rig();
        rig.transactions.failCommit = true;
        assertThrows(IllegalStateException.class, () -> rig.register.register(registration()));
        assertEquals(0, rig.accounts.created);
        assertTrue(rig.sessions.created.isEmpty());
        assertTrue(rig.tokens.created.isEmpty());
        assertEquals(0, rig.transactions.commits);
    }

    @Test
    void staleLoginFailureUpdateAndSignerErrorsCannotCommitSuccess() {
        Rig rig = new Rig();
        rig.register.register(registration());
        rig.accounts.failCompareAndSet = true;
        assertThrows(IllegalStateException.class, () -> rig.login.login(login("wrong-password")));
        assertEquals(0, rig.accounts.failureState.failedAttempts());
        rig.accounts.failCompareAndSet = false;
        rig.issuer.fail = true;
        assertThrows(IllegalStateException.class, () -> rig.login.login(login(CORRECT)));
        assertEquals(1, rig.sessions.created.size());
        assertEquals(1, rig.tokens.created.size());
    }

    private static RegistrationCommand registration() {
        return RegistrationCommand.from("User@example.org", CORRECT, PASSWORDS);
    }

    private static LoginCommand login(String password) {
        return LoginCommand.from(" user@EXAMPLE.org ", password, PASSWORDS);
    }

    private static <E> AuthenticationResult success(Result<AuthenticationResult, E> result) {
        return ((Result.Ok<AuthenticationResult, E>) result).value();
    }

    private static final class Rig {
        final FakeTransactions transactions = new FakeTransactions();
        final FakeAccounts accounts = new FakeAccounts();
        final FakeSessions sessions = new FakeSessions();
        final FakeRefreshTokens tokens = new FakeRefreshTokens();
        final FakeEncoder encoder = new FakeEncoder();
        final FakeIssuer issuer = new FakeIssuer();
        final InitialAuthentication initial = new InitialAuthentication(sessions, tokens,
                new ApplicationTokenGenerator(), SessionPolicy.initial(), issuer);
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final RecordingUserEvents events = new RecordingUserEvents();
        final RegistrationService register = new RegistrationService(transactions, accounts, encoder, initial,
                events, clock);
        final LoginService login = new LoginService(transactions, accounts, encoder, initial,
                LoginLockoutPolicy.initial(), clock, "dummy-verifier");

        Rig() {
            transactions.state = this;
        }
    }

    /** Records publication calls; transactional atomicity is covered by UserRegisteredPublicationE2E. */
    private static final class RecordingUserEvents implements UserEventPublisher {
        final List<UUID> registered = new ArrayList<>();
        final List<Instant> registeredAt = new ArrayList<>();

        @Override
        public void userRegistered(UUID userId, Instant at) {
            registered.add(userId);
            registeredAt.add(at);
        }
    }

    private static final class FakeTransactions implements Transactions {
        Rig state;
        int commits;
        int rollbacks;
        boolean failCommit;

        @Override
        public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
            FakeAccounts accounts = state.accounts;
            FakeSessions sessions = state.sessions;
            FakeRefreshTokens tokens = state.tokens;
            UUID savedId = accounts.userId;
            String savedHash = accounts.passwordHash;
            EmailAddress savedEmail = accounts.email;
            Instant savedDisabled = accounts.disabledAt;
            LoginLockoutPolicy.State savedFailures = accounts.failureState;
            int savedAccounts = accounts.created;
            List<LoginSession> savedSessions = new ArrayList<>(sessions.created);
            List<RefreshTokenRecord> savedTokens = new ArrayList<>(tokens.created);
            try {
                TransactionOutcome<T> outcome = operation.get();
                if (outcome.decision() == TransactionDecision.COMMIT && !failCommit) {
                    commits++;
                    return outcome.result();
                }
                if (failCommit && outcome.decision() == TransactionDecision.COMMIT) {
                    throw new IllegalStateException("Simulated physical commit failure");
                }
                restore(accounts, sessions, tokens, savedId, savedHash, savedEmail, savedDisabled,
                        savedFailures, savedAccounts, savedSessions, savedTokens);
                rollbacks++;
                return outcome.result();
            } catch (RuntimeException | Error failure) {
                restore(accounts, sessions, tokens, savedId, savedHash, savedEmail, savedDisabled,
                        savedFailures, savedAccounts, savedSessions, savedTokens);
                rollbacks++;
                throw failure;
            }
        }

        private static void restore(FakeAccounts accounts, FakeSessions sessions, FakeRefreshTokens tokens,
                UUID id, String hash, EmailAddress email, Instant disabled,
                LoginLockoutPolicy.State failures, int created, List<LoginSession> savedSessions,
                List<RefreshTokenRecord> savedTokens) {
            accounts.userId = id;
            accounts.passwordHash = hash;
            accounts.email = email;
            accounts.disabledAt = disabled;
            accounts.failureState = failures;
            accounts.created = created;
            sessions.created.clear();
            sessions.created.addAll(savedSessions);
            tokens.created.clear();
            tokens.created.addAll(savedTokens);
        }

        @Override public void markRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override public boolean isRollbackOnly() { return false; }
    }

    private static final class FakeAccounts implements AccountStore {
        UUID userId;
        EmailAddress email;
        String passwordHash;
        Instant disabledAt;
        LoginLockoutPolicy.State failureState = new LoginLockoutPolicy.State(0, null);
        int created;
        boolean failCompareAndSet;

        @Override
        public Optional<AccountSnapshot> insert(EmailAddress requested, String hash) {
            if (email != null && email.equals(requested)) {
                return Optional.empty();
            }
            userId = UUID.randomUUID();
            email = requested;
            passwordHash = hash;
            created++;
            return snapshot();
        }

        @Override
        public Optional<AccountSnapshot> findByEmail(EmailAddress requested) {
            return email != null && email.equals(requested) ? snapshot() : Optional.empty();
        }

        @Override
        public Optional<AccountSnapshot> findByEmailForUpdate(EmailAddress requested) {
            return findByEmail(requested);
        }

        @Override
        public Optional<AccountSnapshot> findById(UUID requested) {
            return requested.equals(userId) ? snapshot() : Optional.empty();
        }

        @Override
        public Optional<AccountSnapshot> findByIdForUpdate(UUID requested) {
            return findById(requested);
        }

        @Override
        public void replacePassword(UUID requested, String previous, String replacement) {
            if (!requested.equals(userId) || !passwordHash.equals(previous)) {
                throw new IllegalStateException("Stale IAM verifier");
            }
            passwordHash = replacement;
            failureState = new LoginLockoutPolicy.State(0, null);
        }

        private Optional<AccountSnapshot> snapshot() {
            return email == null ? Optional.empty() : Optional.of(new AccountSnapshot(userId, email,
                    passwordHash, NOW, disabledAt, failureState));
        }

        @Override
        public boolean compareAndSetLoginFailures(UUID id, LoginLockoutPolicy.State previous,
                LoginLockoutPolicy.State next) {
            if (failCompareAndSet || !id.equals(userId) || !failureState.equals(previous)) {
                return false;
            }
            failureState = next;
            return true;
        }
    }

    private static final class FakeSessions implements SessionStore {
        final List<LoginSession> created = new ArrayList<>();

        @Override
        public LoginSession insert(UUID userId, SessionPolicy.Deadlines deadlines) {
            LoginSession session = new LoginSession(UUID.randomUUID(), userId, deadlines, null);
            created.add(session);
            return session;
        }
    }

    private static final class FakeRefreshTokens implements RefreshTokenStore {
        final List<RefreshTokenRecord> created = new ArrayList<>();
        boolean fail;
        @Override public UUID newTokenId() { return UUID.randomUUID(); }
        @Override public void insert(RefreshTokenRecord record) {
            if (fail) {
                throw new IllegalStateException("Simulated refresh persistence failure");
            }
            created.add(record);
        }
    }

    private static final class FakeEncoder implements CredentialEncoder {
        int matchesCalls;
        String lastVerified;
        Runnable onMatches;
        @Override public String encode(String rawPassword) { return "encoded:" + rawPassword; }
        @Override public boolean matches(String rawPassword, String encodedPassword) {
            matchesCalls++;
            lastVerified = encodedPassword;
            if (onMatches != null) {
                onMatches.run();
            }
            return ("encoded:" + rawPassword).equals(encodedPassword);
        }
    }

    private static final class FakeIssuer implements AccessTokenIssuer {
        int issued;
        boolean fail;
        @Override public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt,
                Instant expiresAt) {
            if (fail) {
                throw new IllegalStateException("Simulated token signer failure");
            }
            issued++;
            return new IssuedAccessToken("test-token:" + sessionId, expiresAt);
        }
    }
}

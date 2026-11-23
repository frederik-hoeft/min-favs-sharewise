package de.hsh.favs.sharewise.identity.internal.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.IdentityVerificationFailure;
import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.identity.VerifiedIdentity;
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

/** Port/application-service decisions; SQL predicates and physical completion require Phase B E2E. */
class IamIdentityVerificationServiceTests {

    private static final UUID USER = UUID.fromString("019ab800-0000-7000-8000-000000000201");
    private static final UUID SESSION = UUID.fromString("019ab800-0000-7000-8000-000000000202");
    private static final UUID OTHER = UUID.fromString("019ab800-0000-7000-8000-000000000203");
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    @Test
    void eligibleCallerReturnsVerifiedPairAndInstantOnlyAfterCompletedReadSegment() {
        RecordingTransactions transactions = new RecordingTransactions();
        IdentityEligibilityQuery query = (user, session, now) -> {
            assertTrue(transactions.active);
            assertEquals(USER, user);
            assertEquals(SESSION, session);
            assertEquals(NOW, now);
            return true;
        };
        LocalIdentityPort port = port(identity(Optional.of(USER), Optional.of(SESSION)), transactions, query);

        Result<VerifiedIdentity, IdentityVerificationFailure> result = port.verifyCurrent();

        assertEquals(Result.ok(new VerifiedIdentity(USER, SESSION, NOW)), result);
        assertFalse(transactions.active);
        assertEquals(List.of(TransactionDecision.ABSTAIN), transactions.decisions);
    }

    @Test
    void denialDoesNotDiscloseCauseOrChangeState() {
        RecordingTransactions transactions = new RecordingTransactions();
        LocalIdentityPort port = port(identity(Optional.of(USER), Optional.of(SESSION)), transactions,
                (user, session, now) -> false);

        assertEquals(Result.err(IdentityVerificationFailure.INELIGIBLE), port.verifyCurrent());
        assertEquals(List.of(TransactionDecision.ABSTAIN), transactions.decisions);
    }

    @Test
    void sessionOrAccountLookupUsesBothAdmittedIdsAndIsNotCallerSelectable() {
        RecordingTransactions transactions = new RecordingTransactions();
        LocalIdentityPort port = port(identity(Optional.of(USER), Optional.of(OTHER)), transactions,
                (user, session, now) -> USER.equals(user) && SESSION.equals(session));
        assertEquals(Result.err(IdentityVerificationFailure.INELIGIBLE), port.verifyCurrent());
        assertEquals(List.of(TransactionDecision.ABSTAIN), transactions.decisions);
    }

    @Test
    void anonymousAndIncompleteCallerEvidenceCannotReachTheQueryOrStartATransaction() {
        for (IdentityContext identity : List.of(identity(Optional.empty(), Optional.empty()),
                identity(Optional.of(USER), Optional.empty()),
                identity(Optional.empty(), Optional.of(SESSION)))) {
            RecordingTransactions transactions = new RecordingTransactions();
            LocalIdentityPort port = port(identity, transactions, (user, session, now) -> {
                throw new AssertionError("No lookup for an anonymous or incomplete identity");
            });
            assertThrows(UnauthenticatedCallerException.class, port::verifyCurrent);
            assertTrue(transactions.decisions.isEmpty());
        }
    }

    @Test
    void clockInstantAndDeadlineBoundaryAreForwardedToTheQuery() {
        Instant created = NOW.minusSeconds(100);
        Instant idle = NOW.plusSeconds(1);
        Instant absolute = NOW.plusSeconds(100);
        for (Instant instant : List.of(created.minusNanos(1), created, idle.minusNanos(1), idle,
                absolute, absolute.plusSeconds(1))) {
            RecordingTransactions transactions = new RecordingTransactions();
            IdentityEligibilityQuery query = (user, session, at) ->
                    !at.isBefore(created) && at.isBefore(idle) && at.isBefore(absolute);
            IamIdentityVerificationService service = new IamIdentityVerificationService(transactions, query,
                    Clock.fixed(instant, ZoneOffset.UTC));
            Result<VerifiedIdentity, IdentityVerificationFailure> actual = service.verify(USER, SESSION);
            if (!instant.isBefore(created) && instant.isBefore(idle)) {
                assertEquals(Result.ok(new VerifiedIdentity(USER, SESSION, instant)), actual);
            } else {
                assertEquals(Result.err(IdentityVerificationFailure.INELIGIBLE), actual);
            }
        }
    }

    @Test
    void recognizedDatabaseOutageIsUnavailableOnlyAfterReadSegmentCompletes() {
        for (RuntimeException cause : List.<RuntimeException>of(
                new org.jooq.exception.DataAccessException("connection lost"),
                new org.springframework.dao.DataAccessResourceFailureException("database offline"))) {
            EligibilityQueryUnavailableException outage = new EligibilityQueryUnavailableException(cause);
            RecordingTransactions transactions = new RecordingTransactions();
            LocalIdentityPort port = port(identity(Optional.of(USER), Optional.of(SESSION)), transactions,
                    (user, session, now) -> { throw outage; });
            assertEquals(Result.err(IdentityVerificationFailure.UNAVAILABLE), port.verifyCurrent());
            assertFalse(transactions.active);
            assertEquals(List.of(TransactionDecision.ABSTAIN), transactions.decisions);
        }
    }

    @Test
    void unexpectedDefectsAndTransactionFinalizationFailuresPropagateRatherThanAuthorize() {
        RecordingTransactions unexpected = new RecordingTransactions();
        IllegalStateException defect = new IllegalStateException("unexpected predicate defect");
        LocalIdentityPort broken = port(identity(Optional.of(USER), Optional.of(SESSION)), unexpected,
                (user, session, now) -> { throw defect; });
        assertSame(defect, assertThrows(IllegalStateException.class, broken::verifyCurrent));
        assertFalse(unexpected.active);

        // An adapter must explicitly classify infrastructure failures. A raw SQL error
        // may reflect invalid SQL/schema, not a transient outage.
        RecordingTransactions unclassified = new RecordingTransactions();
        org.jooq.exception.DataAccessException sqlDefect =
                new org.jooq.exception.DataAccessException("invalid query");
        LocalIdentityPort invalidQuery = port(identity(Optional.of(USER), Optional.of(SESSION)),
                unclassified, (user, session, now) -> { throw sqlDefect; });
        assertSame(sqlDefect, assertThrows(org.jooq.exception.DataAccessException.class,
                invalidQuery::verifyCurrent));

        RecordingTransactions completion = new RecordingTransactions();
        completion.completionFailure = new IllegalStateException("rollback not confirmed");
        LocalIdentityPort uncertain = port(identity(Optional.of(USER), Optional.of(SESSION)), completion,
                (user, session, now) -> true);
        assertSame(completion.completionFailure,
                assertThrows(IllegalStateException.class, uncertain::verifyCurrent));
        assertEquals(List.of(TransactionDecision.ABSTAIN), completion.decisions);
    }

    @Test
    void verifiedIdentityIsImmutableAndCannotContainNullComponents() {
        assertThrows(NullPointerException.class, () -> new VerifiedIdentity(null, SESSION, NOW));
        assertThrows(NullPointerException.class, () -> new VerifiedIdentity(USER, null, NOW));
        assertThrows(NullPointerException.class, () -> new VerifiedIdentity(USER, SESSION, null));
    }

    private static LocalIdentityPort port(IdentityContext identity, Transactions transactions,
            IdentityEligibilityQuery query) {
        return new LocalIdentityPort(identity, new IamIdentityVerificationService(transactions, query,
                Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static IdentityContext identity(Optional<UUID> user, Optional<UUID> session) {
        return new IdentityContext() {
            @Override public Optional<UUID> userId() { return user; }
            @Override public Optional<UUID> sessionId() { return session; }
        };
    }

    private static final class RecordingTransactions implements Transactions {
        final List<TransactionDecision> decisions = new ArrayList<>();
        boolean active;
        RuntimeException completionFailure;

        @Override
        public <T> T execute(Supplier<TransactionOutcome<T>> action) {
            if (active) { throw new IllegalStateException("nested transaction"); }
            active = true;
            TransactionOutcome<T> outcome;
            try {
                outcome = action.get();
                decisions.add(outcome.decision());
            } finally {
                active = false;
            }
            if (completionFailure != null) { throw completionFailure; }
            return outcome.result();
        }

        @Override public void markRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override public boolean isRollbackOnly() { return false; }
    }
}

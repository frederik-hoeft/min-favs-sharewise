package de.hsh.favs.sharewise.identity.internal.verification;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.identity.IdentityPort;
import de.hsh.favs.sharewise.identity.IdentityVerificationFailure;
import de.hsh.favs.sharewise.identity.VerifiedIdentity;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.InfrastructureTestComponents;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exercises the actual operation scope and SpringTransactions guard with an in-memory physical manager. */
@ComponentTest
@SpringJUnitConfig({InfrastructureTestComponents.class, IdentityTestConfiguration.class,
        IdentityVerificationConfiguration.class, LocalIdentityPortScopeTests.Fixtures.class})
class LocalIdentityPortScopeTests {

    private static final UUID USER = UUID.fromString("019ab800-0000-7000-8000-000000000201");
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    @Autowired OperationDispatcher dispatcher;
    @Autowired PhysicalTransactions physical;
    @Autowired ProbeQuery query;

    @BeforeEach
    void reset() {
        physical.begins.set(0);
        physical.commits.set(0);
        physical.rollbacks.set(0);
        query.calls.set(0);
        query.unavailable = false;
        physical.rollbackFailure = null;
    }

    @Test
    void portRunsBetweenCompletedConsumerSegmentsWithTheSameScopedManager() {
        withUser(USER, () -> dispatcher.dispatch(Entry.class, entry -> {
            assertSame(entry.port(), entry.samePort());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("read", entry.transactions().execute(() -> TransactionOutcome.abstain("read")));
            assertEquals(Result.ok(new VerifiedIdentity(USER, USER, NOW)), entry.port().verifyCurrent());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("write", entry.transactions().execute(() -> TransactionOutcome.commit("write")));
            return null;
        }));
        assertEquals(3, physical.begins.get());
        assertEquals(1, physical.commits.get());
        assertEquals(2, physical.rollbacks.get());
        assertEquals(1, query.calls.get());
    }

    @Test
    void nestedAuthorityCallIsRejectedBeforeQueryWithoutPoisoningFollowingSegment() {
        withUser(USER, () -> dispatcher.dispatch(Entry.class, entry -> {
            assertThrows(IllegalStateException.class, () -> entry.transactions().execute(() -> {
                entry.port().verifyCurrent();
                return TransactionOutcome.commit("must not commit");
            }));
            assertEquals(0, query.calls.get());
            assertEquals(Result.ok(new VerifiedIdentity(USER, USER, NOW)), entry.port().verifyCurrent());
            return null;
        }));
        assertEquals(2, physical.begins.get());
        assertEquals(0, physical.commits.get());
        assertEquals(2, physical.rollbacks.get());
        assertEquals(1, query.calls.get());
    }

    @Test
    void unrelatedAmbientTransactionIsRejectedWithoutExecutingQuery() {
        withUser(USER, () -> dispatcher.dispatch(Entry.class, entry -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(IllegalStateException.class, entry.port()::verifyCurrent);
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThrows(IllegalStateException.class, entry.port()::verifyCurrent);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            assertEquals(0, query.calls.get());
            assertEquals(Result.ok(new VerifiedIdentity(USER, USER, NOW)), entry.port().verifyCurrent());
            return null;
        }));
        assertEquals(1, physical.begins.get());
        assertEquals(1, physical.rollbacks.get());
    }

    @Test
    void classifiedOutageRollsBackPhysicallyAndLeavesManagerReusable() {
        query.unavailable = true;
        withUser(USER, () -> dispatcher.dispatch(Entry.class, entry -> {
            assertEquals(Result.err(IdentityVerificationFailure.UNAVAILABLE), entry.port().verifyCurrent());
            assertEquals(1, physical.rollbacks.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("next read", entry.transactions().execute(() -> TransactionOutcome.abstain("next read")));
            return null;
        }));
        assertEquals(2, physical.begins.get());
        assertEquals(2, physical.rollbacks.get());
        assertEquals(1, query.calls.get());
    }

    @Test
    void failedPhysicalRollbackCannotBeReclassifiedOrFollowedByAnotherSegment() {
        physical.rollbackFailure = new IllegalStateException("rollback failed");
        withUser(USER, () -> dispatcher.dispatch(Entry.class, entry -> {
            assertThrows(IllegalStateException.class, entry.port()::verifyCurrent);
            assertThrows(IllegalStateException.class, () -> entry.transactions().execute(() ->
                    TransactionOutcome.commit("unsafe")));
            return null;
        }));
        assertEquals(1, physical.begins.get());
        assertEquals(0, physical.commits.get());
    }

    @Test
    void distinctOperationsReceiveDistinctPortInstances() {
        IdentityPort first = withUser(USER, () -> dispatcher.dispatch(Entry.class, Entry::port));
        IdentityPort second = withUser(USER, () -> dispatcher.dispatch(Entry.class, Entry::port));
        assertNotSame(first, second);
        assertEquals(0, physical.begins.get());
    }

    record Entry(Transactions transactions, IdentityPort port, IdentityPort samePort) { }

    static final class ProbeQuery implements IdentityEligibilityQuery {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean unavailable;

        @Override
        public Optional<SessionValidityWindow> findSessionValidityWindow(UUID userId, UUID sessionId) {
            assertEquals(USER, userId);
            assertEquals(USER, sessionId);
            calls.incrementAndGet();
            if (unavailable) {
                throw new EligibilityQueryUnavailableException(new IllegalStateException("connection offline"));
            }
            return Optional.of(new SessionValidityWindow(sessionId, NOW.minusSeconds(1),
                    NOW.plusSeconds(1), NOW.plusSeconds(2)));
        }
    }

    static final class PhysicalTransactions extends AbstractPlatformTransactionManager {
        final AtomicInteger begins = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        volatile RuntimeException rollbackFailure;

        @Override protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean PhysicalTransactions transactionManager() { return new PhysicalTransactions(); }
        @Bean ProbeQuery probeQuery() { return new ProbeQuery(); }
        @Bean("iamClock") Clock iamClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

        @Bean @Scope("operation")
        Entry entry(Transactions transactions, IdentityPort port) {
            return new Entry(transactions, port, port);
        }
    }
}

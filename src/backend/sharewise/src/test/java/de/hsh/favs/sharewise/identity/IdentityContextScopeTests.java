package de.hsh.favs.sharewise.identity;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.anonymously;
import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withFailure;
import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Contract-level Group-like consumers use the production dispatcher and test-only IAM input. */
@ComponentTest
@SpringJUnitConfig({OperationScopeTestComponents.class, IdentityTestConfiguration.class,
    IdentityContextScopeTests.GroupFixture.class})
class IdentityContextScopeTests {

    private static final UUID ALLOWED_USER = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be137");
    private static final UUID OTHER_USER = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be138");
    private static final UUID GROUP = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be139");

    @Autowired OperationDispatcher dispatcher;
    @Autowired CleanupCounter cleanup;

    @Test
    void authenticatedCallerIsAvailableToConstructorInjectedGroupServices() {
        withUser(ALLOWED_USER, () -> dispatcher.dispatch(GroupEntry.class, entry -> {
            IdentityContext identity = entry.identity();
            assertSame(identity, entry.reader().identity());
            assertEquals(Optional.of(ALLOWED_USER), identity.userId());
            assertEquals(ALLOWED_USER, identity.requireUserId());
            assertTrue(identity.isAuthenticated());
            assertEquals(ALLOWED_USER, entry.reader().caller());
            assertEquals(ALLOWED_USER, entry.requireAllowedMember(GROUP));
            return null;
        }));
    }

    @Test
    void separateSequentialOperationsDoNotShareIdentityOrBeans() {
        CallerSnapshot first = withUser(ALLOWED_USER,
            () -> dispatcher.dispatch(GroupEntry.class, entry -> snapshot(entry.identity())));
        CallerSnapshot second = withUser(OTHER_USER,
            () -> dispatcher.dispatch(GroupEntry.class, entry -> snapshot(entry.identity())));
        CallerSnapshot anonymous = dispatcher.dispatch(GroupEntry.class, entry -> snapshot(entry.identity()));

        assertNotSame(first.context(), second.context());
        assertNotSame(first.context(), anonymous.context());
        assertEquals(Optional.of(ALLOWED_USER), first.userId());
        assertEquals(Optional.of(OTHER_USER), second.userId());
        assertEquals(Optional.empty(), anonymous.userId());
    }

    @Test
    void concurrentOperationsKeepDifferentCallersAndScopedInstances() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Future<CallerSnapshot> first = executor.submit(() -> dispatchWhileOverlapping(ALLOWED_USER, entered, release));
            Future<CallerSnapshot> second = executor.submit(() -> dispatchWhileOverlapping(OTHER_USER, entered, release));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "Both dispatches must overlap");
            } finally {
                release.countDown();
            }
            CallerSnapshot firstResult = first.get(10, TimeUnit.SECONDS);
            CallerSnapshot secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(Optional.of(ALLOWED_USER), firstResult.userId());
            assertEquals(Optional.of(OTHER_USER), secondResult.userId());
            assertNotSame(firstResult.context(), secondResult.context());
        }
    }

    @Test
    void anonymousCallerHasNoIdentityAndFailsBeforeGroupAuthorization() {
        anonymously(() -> dispatcher.dispatch(GroupEntry.class, entry -> {
            assertEquals(Optional.empty(), entry.identity().userId());
            assertFalse(entry.identity().isAuthenticated());
            assertThrows(UnauthenticatedCallerException.class, entry.identity()::requireUserId);
            assertThrows(UnauthenticatedCallerException.class, () -> entry.requireAllowedMember(GROUP));
            return null;
        }));
    }

    @Test
    void groupAuthorizationDenialIsDistinctFromMissingAuthentication() {
        withUser(OTHER_USER, () -> dispatcher.dispatch(GroupEntry.class, entry -> {
            assertEquals(OTHER_USER, entry.identity().requireUserId());
            assertThrows(GroupAccessDeniedException.class, () -> entry.requireAllowedMember(GROUP));
            return null;
        }));
    }

    @Test
    void callbackFailureCleansUpAndDoesNotContaminateNextDispatch() {
        int before = cleanup.destroyed.get();
        IllegalArgumentException failure = new IllegalArgumentException("group operation failed");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> withUser(ALLOWED_USER,
            () -> dispatcher.dispatch(GroupEntry.class, entry -> {
                entry.resource();
                throw failure;
            }))));
        assertEquals(before + 1, cleanup.destroyed.get());
        assertEquals(Optional.empty(), dispatcher.dispatch(GroupEntry.class, entry -> entry.identity().userId()));
    }

    @Test
    void constructionFailureCleansUpAndNextDispatchIsAnonymous() {
        int before = cleanup.destroyed.get();
        assertThrows(BeanCreationException.class, () -> withUser(ALLOWED_USER,
            () -> dispatcher.dispatch(FailingEntry.class, ignored -> null)));
        assertEquals(before + 1, cleanup.destroyed.get());
        assertEquals(Optional.empty(), dispatcher.dispatch(GroupEntry.class, entry -> entry.identity().userId()));
    }

    @Test
    void identityProviderFailureNeverTurnsIntoAnonymousIdentity() {
        IllegalStateException failure = new IllegalStateException("identity verification failed");
        BeanCreationException thrown = assertThrows(BeanCreationException.class, () -> withFailure(failure,
            () -> dispatcher.dispatch(GroupEntry.class, entry -> entry.identity().userId())));
        assertSame(failure, rootCause(thrown));
        assertEquals(Optional.empty(), dispatcher.dispatch(GroupEntry.class, entry -> entry.identity().userId()));
    }

    private CallerSnapshot dispatchWhileOverlapping(UUID userId, CountDownLatch entered, CountDownLatch release) {
        return withUser(userId, () -> dispatcher.dispatch(GroupEntry.class, entry -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "Concurrent dispatch timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            assertSame(entry.identity(), entry.reader().identity());
            return snapshot(entry.identity());
        }));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static CallerSnapshot snapshot(IdentityContext context) {
        return new CallerSnapshot(context.userId(), context);
    }

    // The context reference is retained only for identity comparisons, never dereferenced after dispatch.
    record CallerSnapshot(Optional<UUID> userId, IdentityContext context) { }

    record GroupReader(IdentityContext identity) {
        UUID caller() { return identity.requireUserId(); }
    }

    record GroupEntry(IdentityContext identity, GroupReader reader, TestMembershipPolicy policy,
                      CleanupResource resource) {
        UUID requireAllowedMember(UUID groupId) {
            UUID caller = identity.requireUserId();
            if (!policy.allows(caller, groupId)) {
                throw new GroupAccessDeniedException();
            }
            return caller;
        }
    }

    static final class GroupAccessDeniedException extends RuntimeException { }

    static final class TestMembershipPolicy {
        boolean allows(UUID userId, UUID groupId) {
            return ALLOWED_USER.equals(userId) && GROUP.equals(groupId);
        }
    }

    static final class CleanupCounter {
        final AtomicInteger destroyed = new AtomicInteger();
    }

    static final class CleanupResource {
        private final CleanupCounter counter;
        CleanupResource(CleanupCounter counter) { this.counter = counter; }
        void destroy() { counter.destroyed.incrementAndGet(); }
    }

    static final class FailingEntry {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GroupFixture {
        @Bean TestMembershipPolicy testMembershipPolicy() { return new TestMembershipPolicy(); }
        @Bean CleanupCounter cleanupCounter() { return new CleanupCounter(); }

        @Bean(destroyMethod = "destroy") @Scope("operation")
        CleanupResource cleanupResource(CleanupCounter counter) {
            return new CleanupResource(counter);
        }

        @Bean @Scope("operation")
        GroupReader groupReader(IdentityContext identity) { return new GroupReader(identity); }

        @Bean @Scope("operation")
        GroupEntry groupEntry(IdentityContext identity, GroupReader reader,
                              TestMembershipPolicy policy, CleanupResource resource) {
            return new GroupEntry(identity, reader, policy, resource);
        }

        @Bean @Scope("operation")
        FailingEntry failingEntry(IdentityContext identity, CleanupResource resource) {
            throw new IllegalStateException("entry construction failed");
        }
    }
}

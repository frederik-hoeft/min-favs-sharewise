package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimMatch;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimProvider;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimSet;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimSource;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimsContext;
import de.hsh.favs.sharewise.shared.infra.claims.internal.ClaimsConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;

/** Tests the generic claims layer through Spring's real lexical operation scope. */
class ClaimsContextTests {

    @Test
    void preservesRepeatedClaimsAndCorrelationsWithinDistinctVerifiedCredentials() {
        try (AnnotationConfigApplicationContext spring = newContext(MultipleIssuers.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            dispatcher.dispatch(ClaimsContext.class, context -> {
                assertEquals(3, context.claimSets().size());
                List<ClaimMatch> roles = context.find("role");
                assertEquals(List.of("admin", "member", "viewer", "admin"),
                    roles.stream().map(match -> match.claim().value()).toList());
                assertSame(context.claimSets().get(0), roles.get(0).claimSet());
                assertSame(context.claimSets().get(0), roles.get(1).claimSet());
                assertSame(context.claimSets().get(1), roles.get(2).claimSet());
                assertSame(context.claimSets().get(2), roles.get(3).claimSet());

                // Matching a role and resource must remain *within* the same credential.
                assertEquals("group-a", context.claimSets().get(0).claims().get(0).value());
                assertEquals("group-b", context.claimSets().get(1).claims().get(0).value());
                assertEquals(3, context.find("issuer-a", "group-attestation", "role").size());
                assertEquals(1, context.find("issuer-b", "group-attestation", "role").size());
                assertTrue(context.find("issuer-b", "access", "role").isEmpty());
                assertThrows(UnsupportedOperationException.class,
                    () -> context.claimSets().add(context.claimSets().get(0)));
                assertThrows(UnsupportedOperationException.class,
                    () -> context.find("role").clear());
                return null;
            });
        }
    }

    @Test
    void copiesSourceAndClaimsCollectionsDefensivelyAndRejectsInvalidMetadata() {
        Set<String> audiences = new LinkedHashSet<>(List.of("api", "worker"));
        ClaimSource source = new ClaimSource("issuer", "access", audiences, Optional.of("user"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Instant.parse("2028-01-01T00:00:00Z")));
        List<Claim> claims = new ArrayList<>(List.of(new Claim("role", "admin")));
        ClaimSet set = new ClaimSet(source, claims);
        audiences.clear();
        claims.clear();
        assertEquals(Set.of("api", "worker"), source.audiences());
        assertEquals(List.of(new Claim("role", "admin")), set.claims());
        assertThrows(UnsupportedOperationException.class, () -> source.audiences().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> set.claims().add(new Claim("role", "viewer")));
        assertThrows(NullPointerException.class, () -> new Claim("type", null));
        assertThrows(IllegalArgumentException.class, () -> new Claim("  ", "x"));
        assertThrows(IllegalArgumentException.class, () -> sourceWithAudiences(Set.of("  ")));
        assertThrows(IllegalArgumentException.class, () -> sourceWithIssuer(" "));
        assertThrows(NullPointerException.class, () -> new ClaimMatch(set, null));
        assertThrows(NullPointerException.class, () -> new ClaimSet(source, Arrays.asList((Claim) null)));
        assertThrows(NullPointerException.class, () -> sourceWithAudiences(null));
    }

    @Test
    void snapshotsOncePerOperationAndNeverRerunsProvidersOnQuery() {
        try (AnnotationConfigApplicationContext spring = newContext(CountingProvider.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            Probe probe = spring.getBean(Probe.class);
            ClaimsContext first = dispatcher.dispatch(ClaimsContext.class, context -> {
                assertSame(context, spring.getBean(ClaimsContext.class));
                assertEquals(List.of("1"), context.find("sequence").stream()
                    .map(match -> match.claim().value()).toList());
                assertEquals(1, probe.claimsCalls.get());
                assertEquals(1, context.find("sequence").size());
                assertEquals(1, probe.claimsCalls.get());
                return context;
            });
            ClaimsContext second = dispatcher.dispatch(ClaimsContext.class, context -> {
                assertEquals("2", context.find("sequence").getFirst().claim().value());
                return context;
            });
            assertNotSame(first, second);
            assertEquals("1", first.find("sequence").getFirst().claim().value());
            assertEquals(2, probe.claimsCalls.get());
            assertEquals(2, probe.instances.get());
        }
    }

    @Test
    void optionalAbsenceContributesNothingAndDoesNotImplyAuthentication() {
        try (AnnotationConfigApplicationContext spring = newContext(OptionalProvider.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            dispatcher.dispatch(ClaimsContext.class, claims -> {
                assertTrue(claims.claimSets().isEmpty());
                assertTrue(claims.find("sub").isEmpty());
                assertThrows(IllegalArgumentException.class, () -> claims.find(" "));
                assertThrows(IllegalArgumentException.class, () -> claims.find("", "access", "sub"));
                return null;
            });
        }
    }

    @Test
    void missingOptionalProvidersAlsoProducesEmptySnapshot() {
        try (AnnotationConfigApplicationContext spring = newContext()) {
            ClaimsContext result = spring.getBean(OperationDispatcher.class)
                .dispatch(ClaimsContext.class, context -> context);
            assertTrue(result.claimSets().isEmpty());
        }
    }

    @Test
    void sourceFailureAbortsWholeSnapshotWithoutReturningPreviousProviderEvidence() {
        try (AnnotationConfigApplicationContext spring = newContext(PartiallyFailingProvider.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            Probe probe = spring.getBean(Probe.class);
            BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> dispatcher.dispatch(ClaimsContext.class, context -> {
                    probe.published.incrementAndGet();
                    return null;
                }));
            assertEquals("invalid signature", failure.getMostSpecificCause().getMessage());
            assertEquals(0, probe.published.get());
            assertEquals(1, probe.claimsCalls.get());
        }
    }

    @Test
    void nullOrMalformedProviderOutputsAreRejectedRatherThanIgnored() {
        for (Class<?> fixture : List.of(NullListProvider.class, NullElementProvider.class)) {
            try (AnnotationConfigApplicationContext spring = newContext(fixture)) {
                OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
                BeanCreationException failure = assertThrows(BeanCreationException.class,
                    () -> dispatcher.dispatch(ClaimsContext.class, context -> null));
                assertTrue(failure.getMostSpecificCause() instanceof NullPointerException);
            }
        }
    }

    @Test
    void providerMustBeOperationScopedRatherThanSingletonOrPrototype() {
        for (Class<?> fixture : List.of(SingletonProvider.class, PrototypeProvider.class)) {
            try (AnnotationConfigApplicationContext spring = newContext(fixture)) {
                OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
                BeanCreationException failure = assertThrows(BeanCreationException.class,
                    () -> dispatcher.dispatch(ClaimsContext.class, context -> null));
                assertTrue(failure.getMostSpecificCause() instanceof IllegalStateException);
                assertTrue(failure.getMostSpecificCause().getMessage().contains("operation scope"));
            }
        }
    }

    @Test
    void snapshotIsBoundToLexicalScopeAndIndependentAcrossNestedScopes() {
        try (AnnotationConfigApplicationContext spring = newContext(CountingProvider.class)) {
            ServiceScopeFactory factory = spring.getBean(ServiceScopeFactory.class);
            factory.createScope().close();
            try (de.hsh.favs.sharewise.shared.infra.di.ServiceScope outer = factory.createScope()) {
                outer.run(provider -> {
                    ClaimsContext first = provider.getRequiredService(ClaimsContext.class);
                    try (de.hsh.favs.sharewise.shared.infra.di.ServiceScope inner = factory.createScope()) {
                        inner.run(innerProvider -> {
                            ClaimsContext second = innerProvider.getRequiredService(ClaimsContext.class);
                            assertNotSame(first, second);
                            assertEquals("2", second.find("sequence").getFirst().claim().value());
                            return null;
                        });
                    }
                    assertSame(first, provider.getRequiredService(ClaimsContext.class));
                    return null;
                });
            }
        }
    }

    @Test
    void concurrentOperationsNeverShareProviderInstancesOrSnapshots() throws Exception {
        try (AnnotationConfigApplicationContext spring = newContext(CountingProvider.class);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                results.add(executor.submit(() -> dispatcher.dispatch(ClaimsContext.class, claims -> {
                    String value = claims.find("sequence").getFirst().claim().value();
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent operation timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return value;
                })));
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(Set.of("1", "2"), Set.of(results.get(0).get(10, TimeUnit.SECONDS),
                results.get(1).get(10, TimeUnit.SECONDS)));
            Probe probe = spring.getBean(Probe.class);
            assertEquals(2, probe.instances.get());
            assertEquals(2, probe.claimsCalls.get());
        }
    }

    private static ClaimSource sourceWithIssuer(String issuer) {
        return new ClaimSource(issuer, "access", Set.of("api"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ClaimSource sourceWithAudiences(Set<String> audiences) {
        return new ClaimSource("issuer", "access", audiences, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ClaimSet set(String issuer, String subject, Claim... claims) {
        ClaimSource source = new ClaimSource(issuer, "group-attestation", Set.of("sharewise"),
            Optional.of(subject), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new ClaimSet(source, List.of(claims));
    }

    private static AnnotationConfigApplicationContext newContext(Class<?>... fixtures) {
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        spring.register(OperationScopeConfiguration.class, ClaimsConfiguration.class);
        if (fixtures.length != 0) {
            spring.register(fixtures);
        }
        spring.refresh();
        return spring;
    }

    static final class Probe {
        final AtomicInteger instances = new AtomicInteger();
        final AtomicInteger claimsCalls = new AtomicInteger();
        final AtomicInteger published = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MultipleIssuers {
        @Bean @Order(-1) @Scope("operation") ClaimProvider alpha() {
            return () -> List.of(set("issuer-a", "alice", new Claim("group_id", "group-a"),
                    new Claim("role", "admin"), new Claim("role", "member")),
                set("issuer-b", "alice", new Claim("group_id", "group-b"), new Claim("role", "viewer")));
        }

        @Bean @Order(1) @Scope("operation") ClaimProvider beta() {
            return () -> List.of(set("issuer-a", "bob", new Claim("group_id", "group-c"),
                new Claim("role", "admin")));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingProvider {
        @Bean Probe probe() { return new Probe(); }

        @Bean @Scope("operation") ClaimProvider sequence(Probe probe) {
            int sequence = probe.instances.incrementAndGet();
            return () -> {
                probe.claimsCalls.incrementAndGet();
                return List.of(set("issuer", "person", new Claim("sequence", Integer.toString(sequence))));
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OptionalProvider {
        @Bean @Scope("operation") ClaimProvider optional() { return List::of; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PartiallyFailingProvider {
        @Bean Probe probe() { return new Probe(); }
        @Bean @Order(-1) @Scope("operation") ClaimProvider first(Probe probe) {
            return () -> {
                probe.claimsCalls.incrementAndGet();
                return List.of(set("issuer", "user", new Claim("sub", "user")));
            };
        }
        @Bean @Order(1) @Scope("operation") ClaimProvider invalid() {
            return () -> { throw new IllegalArgumentException("invalid signature"); };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NullListProvider {
        @Bean @Scope("operation") ClaimProvider invalid() { return () -> null; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NullElementProvider {
        @Bean @Scope("operation") ClaimProvider invalid() { return () -> Arrays.asList((ClaimSet) null); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SingletonProvider {
        @Bean ClaimProvider invalid() { return List::of; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrototypeProvider {
        @Bean @Scope("prototype") ClaimProvider invalid() { return List::of; }
    }
}

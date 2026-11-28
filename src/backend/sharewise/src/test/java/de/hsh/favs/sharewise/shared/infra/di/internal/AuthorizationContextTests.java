package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import de.hsh.favs.sharewise.shared.infra.claims.internal.AuthorizationConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
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

/** Tests the authorization-evidence layer through Spring's real lexical operation scope. */
class AuthorizationContextTests {

    @Test
    void preservesRepeatedClaimsAndCorrelationsWithinDistinctVerifiedCredentials() {
        try (AnnotationConfigApplicationContext spring = newContext(MultipleIssuers.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            dispatcher.dispatch(AuthorizationContext.class, context -> {
                assertEquals(4, context.credentials().size());
                assertEquals("issuer-a", context.credentials().getFirst().origin().issuer());
                List<Claim> roles = context.find("role");
                assertEquals(List.of("admin", "member", "viewer", "admin"),
                    roles.stream().map(Claim::value).toList());
                assertSame(context.credentials().get(0).origin(), roles.get(0).origin());
                assertSame(context.credentials().get(0).origin(), roles.get(1).origin());
                assertSame(context.credentials().get(1).origin(), roles.get(2).origin());
                assertSame(context.credentials().get(2).origin(), roles.get(3).origin());

                // Matching a role and resource must remain *within* the same credential.
                assertEquals("group-a", context.credentials().get(0).claims().get(0).value());
                assertEquals("group-b", context.credentials().get(1).claims().get(0).value());
                assertEquals(3, context.find("issuer-a", "group-attestation", "role").size());
                assertEquals(1, context.find("issuer-b", "group-attestation", "role").size());
                assertTrue(context.find("issuer-b", "access", "role").isEmpty());
                assertEquals(2, context.credentialsFrom("issuer-a", "group-attestation").size());
                assertEquals(1, context.credentialsFrom(context.credentials().getFirst().origin()).size());
                assertEquals(0, context.credentialsFrom("issuer-a", "access").size());
                assertEquals(Optional.empty(), context.findCredential("issuer-a", "group-attestation", "missing"));
                assertThrows(IllegalArgumentException.class, () -> context.credentialsFrom("", "access"));
                assertThrows(NullPointerException.class, () -> context.credentialsFrom((ClaimOrigin) null));
                assertThrows(UnsupportedOperationException.class,
                    () -> context.credentials().add(context.credentials().get(0)));
                assertThrows(UnsupportedOperationException.class,
                    () -> context.find("role").clear());
                return null;
            });
        }
    }

    @Test
    void lookupByCredentialIdIsQualifiedAndRefusesAmbiguousIdentifiers() {
        try (AnnotationConfigApplicationContext spring = newContext(IdentifiedCredentials.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            dispatcher.dispatch(AuthorizationContext.class, context -> {
                assertEquals(USER.toString(), context.findCredential("iam", "access", "unique")
                    .orElseThrow().origin().subject().orElseThrow());
                assertEquals(Optional.empty(), context.findCredential("other", "access", "unique"));
                assertThrows(IllegalStateException.class,
                    () -> context.findCredential("iam", "access", "duplicated"));
                assertEquals(2, context.credentialsFrom(context.credentials().getFirst().origin()).size());
                return null;
            });
        }
    }

    @Test
    void copiesOriginAndClaimsDefensivelyAndRejectsInvalidMetadata() {
        Set<String> audiences = new LinkedHashSet<>(List.of("api", "worker"));
        ClaimOrigin origin = new ClaimOrigin("issuer", "access", audiences, Optional.of("user"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Instant.parse("2028-01-01T00:00:00Z")));
        List<Claim> claims = new ArrayList<>(List.of(new Claim("role", "admin", origin)));
        AuthorizationCredential credential = new AuthorizationCredential(origin, claims);
        audiences.clear();
        claims.clear();
        assertEquals(Set.of("api", "worker"), origin.audiences());
        assertEquals(List.of(new Claim("role", "admin", origin)), credential.claims());
        assertThrows(UnsupportedOperationException.class, () -> origin.audiences().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> credential.claims().add(new Claim("role", "viewer", origin)));
        assertThrows(NullPointerException.class, () -> new Claim("type", null, origin));
        assertThrows(IllegalArgumentException.class, () -> new Claim("  ", "x", origin));
        assertThrows(IllegalArgumentException.class, () -> originWithAudiences(Set.of("  ")));
        assertThrows(IllegalArgumentException.class, () -> originWithIssuer(" "));
        assertThrows(NullPointerException.class, () -> new Claim("type", "value", null));
        assertThrows(NullPointerException.class, () -> new AuthorizationCredential(origin, Arrays.asList((Claim) null)));
        assertThrows(NullPointerException.class, () -> originWithAudiences(null));
    }

    @Test
    void rejectsClaimsWhoseOriginDiffersFromTheirCredential() {
        ClaimOrigin origin = new ClaimOrigin("iam", "access", Set.of("sharewise"),
            Optional.of("alice"), Optional.of("credential-1"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ClaimOrigin identical = new ClaimOrigin("iam", "access", Set.of("sharewise"),
            Optional.of("alice"), Optional.of("credential-1"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ClaimOrigin otherCredential = new ClaimOrigin("iam", "access", Set.of("sharewise"),
            Optional.of("alice"), Optional.of("credential-2"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ClaimOrigin otherSubject = new ClaimOrigin("iam", "access", Set.of("sharewise"),
            Optional.of("bob"), Optional.of("credential-1"),
            Optional.empty(), Optional.empty(), Optional.empty());

        Claim matching = new Claim("role", "admin", origin);
        assertEquals(List.of(matching), new AuthorizationCredential(identical, List.of(matching)).claims());
        assertEquals(List.of(), new AuthorizationCredential(origin, List.of()).claims());
        assertThrows(IllegalArgumentException.class,
            () -> new AuthorizationCredential(origin, List.of(matching, new Claim("role", "admin", otherCredential))));
        assertThrows(IllegalArgumentException.class,
            () -> new AuthorizationCredential(origin, List.of(new Claim("sub", "bob", otherSubject))));
        assertThrows(NullPointerException.class, () -> new AuthorizationCredential(null, List.of(matching)));
        assertThrows(NullPointerException.class, () -> new AuthorizationCredential(origin, null));
    }

    @Test
    void snapshotsOncePerOperationAndNeverRerunsProvidersOnQuery() {
        try (AnnotationConfigApplicationContext spring = newContext(CountingProvider.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            Probe probe = spring.getBean(Probe.class);
            AuthorizationContext first = dispatcher.dispatch(AuthorizationContext.class, context -> {
                assertSame(context, spring.getBean(AuthorizationContext.class));
                assertEquals(List.of("1"), context.find("sequence").stream()
                    .map(Claim::value).toList());
                assertEquals(1, probe.credentialsCalls.get());
                assertEquals(1, context.find("sequence").size());
                assertEquals(1, probe.credentialsCalls.get());
                return context;
            });
            AuthorizationContext second = dispatcher.dispatch(AuthorizationContext.class, context -> {
                assertEquals("2", context.find("sequence").getFirst().value());
                return context;
            });
            assertNotSame(first, second);
            assertEquals("1", first.find("sequence").getFirst().value());
            assertEquals(2, probe.credentialsCalls.get());
            assertEquals(2, probe.instances.get());
        }
    }

    @Test
    void optionalAbsenceContributesNothingAndDoesNotImplyAuthentication() {
        try (AnnotationConfigApplicationContext spring = newContext(OptionalProvider.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            dispatcher.dispatch(AuthorizationContext.class, claims -> {
                assertTrue(claims.credentials().isEmpty());
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
            AuthorizationContext result = spring.getBean(OperationDispatcher.class)
                .dispatch(AuthorizationContext.class, context -> context);
            assertTrue(result.credentials().isEmpty());
        }
    }

    @Test
    void sourceFailureAbortsWholeSnapshotWithoutReturningPreviousProviderEvidence() {
        try (AnnotationConfigApplicationContext spring = newContext(PartiallyFailingProvider.class)) {
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            Probe probe = spring.getBean(Probe.class);
            BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> dispatcher.dispatch(AuthorizationContext.class, context -> {
                    probe.published.incrementAndGet();
                    return null;
                }));
            assertEquals("invalid signature", failure.getMostSpecificCause().getMessage());
            assertEquals(0, probe.published.get());
            assertEquals(1, probe.credentialsCalls.get());
        }
    }

    @Test
    void nullOrMalformedProviderOutputsAreRejectedRatherThanIgnored() {
        for (Class<?> fixture : List.of(NullListProvider.class, NullElementProvider.class)) {
            try (AnnotationConfigApplicationContext spring = newContext(fixture)) {
                OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
                BeanCreationException failure = assertThrows(BeanCreationException.class,
                    () -> dispatcher.dispatch(AuthorizationContext.class, context -> null));
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
                    () -> dispatcher.dispatch(AuthorizationContext.class, context -> null));
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
                    AuthorizationContext first = provider.getRequiredService(AuthorizationContext.class);
                    try (de.hsh.favs.sharewise.shared.infra.di.ServiceScope inner = factory.createScope()) {
                        inner.run(innerProvider -> {
                            AuthorizationContext second = innerProvider.getRequiredService(AuthorizationContext.class);
                            assertNotSame(first, second);
                            assertEquals("2", second.find("sequence").getFirst().value());
                            return null;
                        });
                    }
                    assertSame(first, provider.getRequiredService(AuthorizationContext.class));
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
                results.add(executor.submit(() -> dispatcher.dispatch(AuthorizationContext.class, claims -> {
                    String value = claims.find("sequence").getFirst().value();
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
            assertEquals(2, probe.credentialsCalls.get());
        }
    }

    private static ClaimOrigin originWithIssuer(String issuer) {
        return new ClaimOrigin(issuer, "access", Set.of("api"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ClaimOrigin originWithAudiences(Set<String> audiences) {
        return new ClaimOrigin("issuer", "access", audiences, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private record ClaimData(String type, String value) {}

    private static ClaimData claim(String type, String value) {
        return new ClaimData(type, value);
    }

    private static final UUID USER = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final JwtCredentialProfile PRIMARY = profile("primary", "access", true);
    private static final Es256JwtSignatureService SIGNATURE = signature();

    private static Es256JwtSignatureService signature() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return new Es256JwtSignatureService(List.of(JwtEcKey.signing("fixture", generator.generateKeyPair())),
                Optional.of("fixture"));
        } catch (java.security.GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    private static JwtCredentialProfile profile(String issuer, String name, boolean primary) {
        return new JwtCredentialProfile(name, issuer, "sw-" + name + "+jwt", Set.of("sharewise"),
            primary ? JwtCredentialProfile.Kind.PRIMARY : JwtCredentialProfile.Kind.SUBORDINATE,
            primary ? Set.of() : Set.of(PRIMARY.id()), Duration.ofMinutes(5), Duration.ZERO, 4096);
    }

    private static VerifiedJwtCredentialCandidate credential(JwtCredentialProfile profile, String tokenId,
                                                              ClaimData... claims) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(profile.issuer()).subject(USER.toString())
            .audience(List.of("sharewise")).issuedAt(NOW).notBefore(NOW).expiresAt(NOW.plusSeconds(60)).id(tokenId);
        if (profile.kind() == JwtCredentialProfile.Kind.PRIMARY) {
            builder.claim("sid", "fixture-session");
        } else {
            builder.claim("principal_iss", PRIMARY.issuer()).claim("principal_sid", "fixture-session");
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (ClaimData data : claims) {
            values.computeIfAbsent(data.type(), ignored -> new ArrayList<>()).add(data.value());
        }
        values.forEach((name, entries) -> builder.claim(name, entries.size() == 1 ? entries.getFirst() : entries));
        JwtCredentialCodec codec = new JwtCredentialCodec(profile, SIGNATURE, Clock.fixed(NOW, ZoneOffset.UTC));
        return codec.verify(SIGNATURE.sign(profile.tokenType(), builder.build()));
    }

    private static AnnotationConfigApplicationContext newContext(Class<?>... fixtures) {
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        spring.register(OperationScopeConfiguration.class, AuthorizationConfiguration.class);
        if (fixtures.length != 0) {
            spring.register(fixtures);
        }
        spring.refresh();
        return spring;
    }

    static final class Probe {
        final AtomicInteger instances = new AtomicInteger();
        final AtomicInteger credentialsCalls = new AtomicInteger();
        final AtomicInteger published = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentifiedCredentials {
        @Bean @Scope("operation") AuthorizationCredentialProvider provider() {
            JwtCredentialProfile profile = profile("iam", "access", false);
            return () -> List.of(
                credential(profile, "duplicated", claim("role", "user")),
                credential(profile, "duplicated", claim("role", "member")),
                credential(profile, "unique", claim("role", "admin")),
                credential(PRIMARY, "primary"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MultipleIssuers {
        @Bean @Order(-1) @Scope("operation") AuthorizationCredentialProvider alpha() {
            return () -> List.of(credential(profile("issuer-a", "group-attestation", false), "a", claim("group_id", "group-a"),
                    claim("role", "admin"), claim("role", "member")),
                credential(profile("issuer-b", "group-attestation", false), "b", claim("group_id", "group-b"), claim("role", "viewer")));
        }

        @Bean @Order(1) @Scope("operation") AuthorizationCredentialProvider beta() {
            return () -> List.of(credential(profile("issuer-a", "group-attestation", false), "c", claim("group_id", "group-c"),
                claim("role", "admin")), credential(PRIMARY, "primary"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingProvider {
        @Bean Probe probe() { return new Probe(); }

        @Bean @Scope("operation") AuthorizationCredentialProvider sequence(Probe probe) {
            int sequence = probe.instances.incrementAndGet();
            return () -> {
                probe.credentialsCalls.incrementAndGet();
                return List.of(credential(PRIMARY, "sequence-" + sequence, claim("sequence", Integer.toString(sequence))));
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OptionalProvider {
        @Bean @Scope("operation") AuthorizationCredentialProvider optional() { return List::of; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PartiallyFailingProvider {
        @Bean Probe probe() { return new Probe(); }
        @Bean @Order(-1) @Scope("operation") AuthorizationCredentialProvider first(Probe probe) {
            return () -> {
                probe.credentialsCalls.incrementAndGet();
                return List.of(credential(PRIMARY, "first"));
            };
        }
        @Bean @Order(1) @Scope("operation") AuthorizationCredentialProvider invalid() {
            return () -> { throw new IllegalArgumentException("invalid signature"); };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NullListProvider {
        @Bean @Scope("operation") AuthorizationCredentialProvider invalid() { return () -> null; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NullElementProvider {
        @Bean @Scope("operation") AuthorizationCredentialProvider invalid() { return () -> Arrays.asList((VerifiedJwtCredentialCandidate) null); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SingletonProvider {
        @Bean AuthorizationCredentialProvider invalid() { return List::of; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrototypeProvider {
        @Bean @Scope("prototype") AuthorizationCredentialProvider invalid() { return List::of; }
    }
}

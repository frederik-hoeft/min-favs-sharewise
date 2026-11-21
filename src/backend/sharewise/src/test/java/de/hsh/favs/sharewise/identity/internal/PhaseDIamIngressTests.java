package de.hsh.favs.sharewise.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import java.util.List;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import de.hsh.favs.sharewise.shared.infra.claims.internal.AuthorizationConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputContributor;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import de.hsh.favs.sharewise.shared.infra.di.ContributingOperationDispatcher;
import de.hsh.favs.sharewise.identity.internal.web.IamBearerIngressFilter;
import de.hsh.favs.sharewise.identity.internal.web.IamHttpSecurityConfiguration;
import de.hsh.favs.sharewise.identity.internal.web.IamIdentityExceptionHandler;
import de.hsh.favs.sharewise.identity.internal.web.HttpIamInputContributor;
import org.springframework.security.web.FilterChainProxy;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import jakarta.servlet.ServletException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Production IAM/DI/claims components, with only trust keys and operation entry supplied by the fixture. */
@ComponentTest
@WebAppConfiguration
@SpringJUnitConfig({OperationScopeTestComponents.class, AuthorizationConfiguration.class,
    PhaseDIamIngressTests.Fixture.class})
class PhaseDIamIngressTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ALICE = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID BOB = UUID.fromString("87654321-4321-4432-8432-ba0987654321");
    private static final JwtCredentialProfile PROFILE = new JwtCredentialProfile(
        "iam-access", "https://iam.sharewise.test", "sw-iam-access+jwt", Set.of("sharewise-api"),
        JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
    private static final KeyPair KEY = generateKey();

    @Autowired OperationIngressDispatcher httpIngress;
    @Autowired FilterChainProxy filter;
    @Autowired IamIdentityExceptionHandler advice;
    @Autowired OperationDispatcher dispatcher;
    @Autowired @Qualifier("iamPrimaryJwtCredentialCodec") JwtCredentialCodec codec;
    @Autowired IamCredentialVerifier verifier;

    @Test
    void verifiedIdentityIsStableThroughIndependentConsumersAndStubbedTransactionSegments() {
        String token = token(ALICE, "session-alice");
        Snapshot authenticated = bearer(token).dispatch(Entry.class, Entry::snapshot);
        assertEquals(Optional.of(ALICE), authenticated.userId());
        assertEquals(ALICE, authenticated.requiredUser());
        assertTrue(authenticated.authenticated());
        assertEquals(1, authenticated.credentialCount());
        assertEquals("session-alice", authenticated.session());
        assertTrue(authenticated.sameIdentityInstance());
        assertTrue(authenticated.sameAuthorizationInstance());
        assertEquals(authenticated.segmentOne(), authenticated.segmentTwo());

        Snapshot other = bearer(token(BOB, "session-bob")).dispatch(Entry.class, Entry::snapshot);
        Optional<UUID> anonymous = anonymous().dispatch(IdentityContext.class, IdentityContext::userId);
        assertEquals(Optional.of(BOB), other.userId());
        assertEquals("session-bob", other.session());
        assertEquals(Optional.empty(), anonymous);
        int anonymousCredentialCount = anonymous().dispatch(AuthorizationContext.class, context -> context.credentials().size());
        assertEquals(0, anonymousCredentialCount);
        assertNotSame(authenticated.identity(), other.identity());
        assertNotSame(authenticated.authorization(), anonymous().dispatch(AuthorizationContext.class, context -> context));
        assertEquals(Optional.empty(), anonymous().dispatch(IdentityContext.class, IdentityContext::userId));
    }

    @Test
    void failuresCannotTurnIntoAnonymousAndDirectDispatchWithoutIngressIsRejected() {
        String token = token(ALICE, "session-a");
        assertThrows(JwtCredentialException.class, () -> bearer("not-a-jwt").dispatch(Entry.class, Entry::snapshot));
        assertThrows(JwtCredentialException.class, () -> bearer(token + "bad").dispatch(Entry.class, Entry::snapshot));
        assertThrows(JwtCredentialException.class, () -> bearer(null).dispatch(Entry.class, Entry::snapshot));
        assertEquals(Optional.empty(), dispatcher.dispatch(IdentityContext.class, IdentityContext::userId));
        assertEquals(Optional.empty(), anonymous().dispatch(IdentityContext.class, IdentityContext::userId));
        assertThrows(UnauthenticatedCallerException.class,
            () -> anonymous().dispatch(IdentityContext.class, IdentityContext::requireUserId));
    }

    @Test
    void semanticIdentityRejectsAmbiguousPrimaryOrCorruptSessionWithoutSelectingTheFirst() {
        AuthorizationCredential first = codec.verifyPrimary(token(ALICE, "s1")).credential();
        AuthorizationCredential second = codec.verifyPrimary(token(BOB, "s2")).credential();
        assertThrows(IllegalStateException.class,
            () -> new DefaultIdentityContext(evidence(List.of(first, second)), verifier));
        assertThrows(IllegalStateException.class,
            () -> new DefaultIdentityContext(evidence(List.of(second, first)), verifier));

        ClaimOrigin origin = first.origin();
        AuthorizationCredential missingSession = new AuthorizationCredential(origin,
            first.claims().stream().filter(claim -> !"sid".equals(claim.type())).toList());
        assertThrows(IllegalStateException.class,
            () -> new DefaultIdentityContext(evidence(List.of(missingSession)), verifier));
        ClaimOrigin differentProfile = new ClaimOrigin(origin.issuer(), "group-attestation", origin.audiences(),
            origin.subject(), origin.credentialId(), origin.issuedAt(), origin.notBefore(), origin.expiresAt());
        AuthorizationCredential subordinateOnly = new AuthorizationCredential(differentProfile,
            List.of(new Claim("member", "true", differentProfile)));
        assertThrows(IllegalStateException.class,
            () -> new DefaultIdentityContext(evidence(List.of(subordinateOnly)), verifier));
    }

    @Test
    void missingRequiredCredentialProviderOrCodecFailsClosed() throws Exception {
        DefaultListableBeanFactory withoutProvider = new DefaultListableBeanFactory();
        withoutProvider.registerSingleton("codec", codec);
        IamCredentialVerifier verifierWithoutProvider = new IamCredentialVerifier(
            withoutProvider.getBeanProvider(JwtCredentialCodec.class), withoutProvider);
        assertThrows(IllegalStateException.class, verifierWithoutProvider::requireCodec);

        DefaultListableBeanFactory withoutCodec = new DefaultListableBeanFactory();
        withoutCodec.registerBeanDefinition("iamPrimaryCredentialProvider",
            BeanDefinitionBuilder.genericBeanDefinition(IamPrimaryCredentialProvider.class).getBeanDefinition());
        IamCredentialVerifier verifierWithoutCodec = new IamCredentialVerifier(
            withoutCodec.getBeanProvider(JwtCredentialCodec.class), withoutCodec);
        assertThrows(IllegalStateException.class, verifierWithoutCodec::requireCodec);

        JwtCredentialProfile tooLarge = new JwtCredentialProfile(PROFILE.name(), PROFILE.issuer(),
            PROFILE.tokenType(), PROFILE.audiences(), PROFILE.kind(), PROFILE.trustedPrimaryProfiles(),
            PROFILE.maximumLifetime(), PROFILE.clockSkew(), 16_384);
        JwtCredentialCodec oversizedCodec = new JwtCredentialCodec(tooLarge,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")), CLOCK);
        DefaultListableBeanFactory oversizedWiring = new DefaultListableBeanFactory();
        oversizedWiring.registerSingleton("iamPrimaryJwtCredentialCodec", oversizedCodec);
        oversizedWiring.registerBeanDefinition("iamPrimaryCredentialProvider",
            BeanDefinitionBuilder.genericBeanDefinition(IamPrimaryCredentialProvider.class).getBeanDefinition());
        IamCredentialVerifier verifierWithOversizedProfile = new IamCredentialVerifier(
            oversizedWiring.getBeanProvider(JwtCredentialCodec.class), oversizedWiring);
        assertThrows(IllegalStateException.class, verifierWithOversizedProfile::requireCodec);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IdentityController(httpIngress))
            .addFilters(new IamBearerIngressFilter(verifierWithoutProvider)).build();
        mvc.perform(get("/api/test/protected"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("security_unavailable"));
        mvc.perform(get("/api/system/v1/health"))
            .andExpect(status().isOk());
    }

    private static AuthorizationContext evidence(List<AuthorizationCredential> credentials) {
        return new AuthorizationContext() {
            @Override public List<AuthorizationCredential> credentials() { return List.copyOf(credentials); }
            @Override public List<AuthorizationCredential> credentialsFrom(ClaimOrigin origin) {
                return credentials.stream().filter(c -> c.origin().equals(origin)).toList();
            }
            @Override public List<AuthorizationCredential> credentialsFrom(String issuer, String profile) {
                return credentials.stream().filter(c -> c.origin().issuer().equals(issuer)
                    && c.origin().profile().equals(profile)).toList();
            }
            @Override public Optional<AuthorizationCredential> findCredential(String issuer, String profile, String id) {
                return credentialsFrom(issuer, profile).stream()
                    .filter(c -> c.origin().credentialId().filter(id::equals).isPresent()).findFirst();
            }
            @Override public List<Claim> find(String type) { throw new UnsupportedOperationException(); }
            @Override public List<Claim> find(String issuer, String profile, String type) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void admittedNonHttpIdentityCanFlowAcrossOperationsAfterTokenExpiry() {
        String jwt = token(ALICE, "workflow-session");
        VerifiedJwtCredential admitted = verifier.verify(jwt);
        JwtCredentialCodec later = new JwtCredentialCodec(PROFILE,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")),
            Clock.offset(CLOCK, Duration.ofSeconds(400)));
        assertThrows(JwtCredentialException.class, () -> later.verifyPrimary(jwt));

        OperationIngressDispatcher continuation = new ContributingOperationDispatcher(dispatcher,
            List.of(inputs -> inputs.add(VerifiedJwtCredential.class, admitted)));
        assertEquals(Optional.of(ALICE), continuation.dispatch(IdentityContext.class, IdentityContext::userId));
        assertEquals(Optional.of(ALICE), continuation.dispatch(IdentityContext.class, IdentityContext::userId));
    }

    @Test
    void concurrentDispatchesAreIsolatedEvenWhenTheirActionsOverlap() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Future<Snapshot> first = executor.submit(() -> overlap(ALICE, "session-a", entered, release));
            Future<Snapshot> second = executor.submit(() -> overlap(BOB, "session-b", entered, release));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            Snapshot a = first.get(10, TimeUnit.SECONDS);
            Snapshot b = second.get(10, TimeUnit.SECONDS);
            assertEquals(Optional.of(ALICE), a.userId());
            assertEquals(Optional.of(BOB), b.userId());
            assertNotSame(a.identity(), b.identity());
            assertNotSame(a.authorization(), b.authorization());
        }
    }

    @Test
    void httpFilterVerifiesBeforeDispatchAndSeparatesAnonymousFromInvalidSuppliedBearer() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IdentityController(httpIngress))
            .setControllerAdvice(advice).addFilters(filter).build();
        MvcResult success = mvc.perform(get("/api/test/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ALICE, "web-session")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(ALICE.toString()))
            .andReturn();
        assertTrue(success.getRequest().getAttribute(IamBearerIngressFilter.VERIFIED_INGRESS_ATTRIBUTE) == null);
        mvc.perform(get("/api/test/public"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("anonymous"));
        mvc.perform(get("/api/test/protected"))
            .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("unauthenticated"));
        mvc.perform(get("/api/test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_credential"));
        mvc.perform(get("/api/test/public").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/public").header(HttpHeaders.AUTHORIZATION, "Bearer  " + token(ALICE, "wrong-space")))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/public").header(HttpHeaders.AUTHORIZATION, "Bearer token1", "Bearer token2"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongProfileToken()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/system/v1/health"))
            .andExpect(status().isOk());
    }

    @Test
    void callerSideRequestContributorsComposeWithoutControllerOrIamDispatcherChanges() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IdentityController(httpIngress))
            .setControllerAdvice(advice).addFilters(filter).build();
        mvc.perform(get("/api/test/contributions").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + token(ALICE, "session-a")).header("X-Additional-Input", "first"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(ALICE.toString()))
            .andExpect(jsonPath("$.additional").value("first"));
        mvc.perform(get("/api/test/contributions").header("X-Additional-Input", "second"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("anonymous"))
            .andExpect(jsonPath("$.additional").value("second"));
        mvc.perform(get("/api/test/contributions"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.additional").value("absent"));
    }

    @Test
    void nonHttpContributorsComposeAndRejectDuplicateRegistration() {
        OperationInputContributor identity = inputs -> inputs.add(VerifiedJwtCredential.class,
            verifier.verify(token(ALICE, "session-a")));
        OperationInputContributor additional = inputs -> inputs.add(AdditionalInput.class, new AdditionalInput("job"));
        OperationIngressDispatcher composed = new ContributingOperationDispatcher(dispatcher,
            List.of(identity, additional));
        assertEquals("job", composed.dispatch(AdditionalConfiguration.class, AdditionalConfiguration::value));
        assertEquals(Optional.of(ALICE), composed.dispatch(IdentityContext.class, IdentityContext::userId));
        OperationIngressDispatcher duplicate = new ContributingOperationDispatcher(dispatcher, List.of(additional, additional));
        assertThrows(IllegalArgumentException.class,
            () -> duplicate.dispatch(AdditionalConfiguration.class, AdditionalConfiguration::value));
    }

    @Test
    void httpOperationCannotBypassAbsentMiddleware() {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IdentityController(httpIngress))
            .setControllerAdvice(advice).build();
        ServletException failure = assertThrows(ServletException.class,
            () -> mvc.perform(get("/api/test/public")));
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    private OperationIngressDispatcher bearer(String token) {
        OperationInputContributor contribution = inputs -> inputs.add(VerifiedJwtCredential.class, verifier.verify(token));
        return new ContributingOperationDispatcher(dispatcher, List.of(contribution));
    }

    private OperationIngressDispatcher anonymous() {
        return new ContributingOperationDispatcher(dispatcher, List.of());
    }

    private Snapshot overlap(UUID who, String session, CountDownLatch entered, CountDownLatch release) {
        return bearer(token(who, session)).dispatch(Entry.class, entry -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return entry.snapshot();
        });
    }

    private String token(UUID user, String session) {
        return codec.sign(new JwtIssueRequest(user, session, "test-token", NOW.minusSeconds(1),
            NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of("device", "test")));
    }

    private String expiredToken() {
        return codec.sign(new JwtIssueRequest(ALICE, "expired", "expired-token", NOW.minusSeconds(240),
            NOW.minusSeconds(240), NOW.minusSeconds(31), Optional.empty(), Map.of()));
    }

    private String wrongProfileToken() {
        JwtCredentialProfile wrong = new JwtCredentialProfile("refresh", PROFILE.issuer(), "sw-refresh+jwt",
            PROFILE.audiences(), PROFILE.kind(), Set.of(), PROFILE.maximumLifetime(), PROFILE.clockSkew(), 4096);
        JwtCredentialCodec other = new JwtCredentialCodec(wrong,
            new Es256JwtSignatureService(java.util.List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")), CLOCK);
        return other.sign(new JwtIssueRequest(ALICE, "wrong-profile", "refresh-token", NOW.minusSeconds(1),
            NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
    }

    private static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    record AdditionalInput(String value) { }

    static final class AdditionalConfiguration extends OperationScopedConfiguration {
        private String value;

        void seed(Optional<AdditionalInput> input) {
            requireInitializing();
            if (value != null) {
                throw new IllegalStateException("Additional input seeded twice");
            }
            value = input.map(AdditionalInput::value).orElse("absent");
        }

        String value() {
            requireReady();
            return value;
        }
    }

    static final class AdditionalInitializer implements ScopedServiceInitializer<AdditionalConfiguration, AdditionalInput> {
        @Override public Class<AdditionalConfiguration> configurationType() { return AdditionalConfiguration.class; }
        @Override public Class<AdditionalInput> inputType() { return AdditionalInput.class; }
        @Override public void initialize(AdditionalConfiguration config, Optional<AdditionalInput> input) {
            config.seed(input);
        }
    }

    record ConsumerA(IdentityContext identity, AuthorizationContext authorization) { }
    record ConsumerB(IdentityContext identity, AuthorizationContext authorization) { }

    // Models successive transaction-segment calls without a PostgreSQL dependency.
    record SegmentBoundary() {
        <R> R invoke(Supplier<R> body) { return body.get(); }
    }

    record Entry(ConsumerA first, ConsumerB second, SegmentBoundary segments) {
        Snapshot snapshot() {
            IdentityContext identity = first.identity();
            AuthorizationContext authorization = first.authorization();
            UUID segmentOne = segments.invoke(identity::requireUserId);
            UUID segmentTwo = segments.invoke(second.identity()::requireUserId);
            String session = authorization.find("sid").getFirst().value();
            return new Snapshot(identity.userId(), segmentOne, identity.isAuthenticated(),
                authorization.credentials().size(), session,
                identity == second.identity(), authorization == second.authorization(),
                segmentOne, segmentTwo, identity, authorization);
        }
    }

    record ContributionEntry(IdentityContext identity, AdditionalConfiguration additional) { }

    record Snapshot(Optional<UUID> userId, UUID requiredUser, boolean authenticated, int credentialCount,
        String session, boolean sameIdentityInstance, boolean sameAuthorizationInstance,
        UUID segmentOne, UUID segmentTwo, IdentityContext identity, AuthorizationContext authorization) { }

    @TestComponent
    @RestController
    static final class IdentityController {
        private final OperationIngressDispatcher ingress;
        IdentityController(OperationIngressDispatcher ingress) { this.ingress = ingress; }

        @GetMapping("/api/test/protected")
        Map<String, String> protectedIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.requireUserId().toString()));
        }

        @GetMapping("/api/test/contributions")
        Map<String, String> contributions() {
            return ingress.dispatch(ContributionEntry.class, entry -> Map.of(
                "userId", entry.identity().userId().map(UUID::toString).orElse("anonymous"),
                "additional", entry.additional().value()));
        }

        @GetMapping("/api/test/public")
        Map<String, String> publicIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.userId().map(UUID::toString).orElse("anonymous")));
        }

        @GetMapping("/api/system/v1/health")
        Map<String, String> publicHealth() { return Map.of("status", "UP"); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({IamOperationSecurityConfiguration.class, IamIngressInitializer.class,
        IamPrimaryCredentialProvider.class, IamCredentialVerifier.class,
        IamHttpSecurityConfiguration.class, HttpIamInputContributor.class, IamIdentityExceptionHandler.class,
        DefaultIdentityContext.class})
    static class Fixture {
        @Bean @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
        OperationInputContributor additionalRequestContributor(jakarta.servlet.http.HttpServletRequest request) {
            return inputs -> {
                String value = request.getHeader("X-Additional-Input");
                if (value != null) {
                    inputs.add(AdditionalInput.class, new AdditionalInput(value));
                }
            };
        }
        @Bean @Scope("operation")
        AdditionalConfiguration additionalConfiguration() { return new AdditionalConfiguration(); }
        @Bean
        ScopedServiceInitializer<AdditionalConfiguration, AdditionalInput> additionalInitializer() {
            return new AdditionalInitializer();
        }
        @Bean @Scope("operation")
        ContributionEntry contributionEntry(IdentityContext identity, AdditionalConfiguration additional) {
            return new ContributionEntry(identity, additional);
        }

        @Bean
        JwtCredentialCodec iamPrimaryJwtCredentialCodec() {
            return new JwtCredentialCodec(PROFILE,
                new Es256JwtSignatureService(java.util.List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")), CLOCK);
        }
        // A second issuer codec must not make primary IAM trust selection ambiguous.
        @Bean
        JwtCredentialCodec unrelatedCredentialCodec() {
            JwtCredentialProfile subordinate = new JwtCredentialProfile("group-attestation",
                "https://groups.sharewise.test", "sw-group-attestation+jwt", Set.of("finance-api"),
                JwtCredentialProfile.Kind.SUBORDINATE, Set.of(PROFILE.id()),
                Duration.ofMinutes(5), Duration.ofSeconds(30), 16_384);
            return new JwtCredentialCodec(subordinate,
                new Es256JwtSignatureService(List.of(JwtEcKey.signing("group", KEY)), Optional.of("group")), CLOCK);
        }
        @Bean @Scope("operation")
        ConsumerA consumerA(IdentityContext identity, AuthorizationContext authorization) {
            return new ConsumerA(identity, authorization);
        }
        @Bean @Scope("operation")
        ConsumerB consumerB(IdentityContext identity, AuthorizationContext authorization) {
            return new ConsumerB(identity, authorization);
        }
        @Bean @Scope("operation")
        SegmentBoundary segmentBoundary() { return new SegmentBoundary(); }
        @Bean @Scope("operation")
        Entry entry(ConsumerA first, ConsumerB second, SegmentBoundary segments) {
            return new Entry(first, second, segments);
        }
    }
}

package de.hsh.favs.sharewise.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.internal.web.IamBearerIngressFilter;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import jakarta.servlet.Filter;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the primary credential path through normal Boot component scanning, MVC and filter registration.
 * Database auto-configuration is excluded so this remains an offline Unit-suite component test.
 */
@ComponentTest
@SpringBootTest(classes = {ShareWiseApplication.class, FullApplicationIamIngressTests.Fixture.class}, properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration",
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class FullApplicationIamIngressTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ALICE = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final JwtCredentialProfile PROFILE = new JwtCredentialProfile(
        "iam-access", "https://iam.sharewise.test", "sw-iam-access+jwt", Set.of("sharewise-api"),
        JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
    private static final KeyPair KEY = generateKey();

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired FilterChainProxy securityFilterChain;
    // This Unit-suite context excludes DataSource auto-configuration. Isolate the
    // real readiness probe so this test verifies HTTP security, not database wiring.
    @MockitoBean(name = "dataSourceReadinessIndicator", enforceOverride = true)
    ReadinessIndicator dataSourceReadinessIndicator;
    @Autowired @Qualifier("iamPrimaryJwtCredentialCodec") JwtCredentialCodec iamPrimaryJwtCredentialCodec;

    @Test
    void bootRegistersOneOrderedSecurityProxyWithoutDuplicatingBearerVerification() {
        assertEquals(1, context.getBeansOfType(FilterChainProxy.class).size());
        assertSame(securityFilterChain, context.getBean("springSecurityFilterChain", FilterChainProxy.class));
        assertEquals(1, context.getBeansOfType(DelegatingFilterProxyRegistrationBean.class).size());
        // The ingress filter is owned by the API chain, never registered as a servlet Filter bean.
        assertTrue(context.getBeansOfType(IamBearerIngressFilter.class).isEmpty());

        List<SecurityFilterChain> chains = securityFilterChain.getFilterChains();
        assertEquals(2, chains.size());
        MockHttpServletRequest apiRoot = new MockHttpServletRequest("GET", "/api");
        MockHttpServletRequest apiChild = new MockHttpServletRequest("GET", "/api/__test/identity/optional");
        MockHttpServletRequest outside = new MockHttpServletRequest("GET", "/public");
        assertTrue(chains.get(0).matches(apiRoot));
        assertTrue(chains.get(0).matches(apiChild));
        assertFalse(chains.get(0).matches(outside));
        assertTrue(chains.get(1).matches(outside));

        List<Filter> apiFilters = chains.get(0).getFilters();
        assertEquals(1, apiFilters.stream().filter(IamBearerIngressFilter.class::isInstance).count());
        assertTrue(apiFilters.stream().anyMatch(AuthorizationFilter.class::isInstance));
        int ingressPosition = indexOf(apiFilters, IamBearerIngressFilter.class);
        int authorizationPosition = indexOf(apiFilters, AuthorizationFilter.class);
        assertTrue(ingressPosition < authorizationPosition);
        assertEquals(0, chains.get(1).getFilters().stream()
            .filter(IamBearerIngressFilter.class::isInstance).count());
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int index = 0; index < filters.size(); index++) {
            if (type.isInstance(filters.get(index))) {
                return index;
            }
        }
        return -1;
    }

    @Test
    void healthAndNonApiEndpointsDoNotRequireTrustedBearerEvidence() throws Exception {
        // Health bypasses bearer validation even when a malformed bearer is supplied.
        when(dataSourceReadinessIndicator.isReady()).thenReturn(true);
        mvc.perform(get("/api/system/v1/health").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        // Non-API routes are not intercepted by IAM's verifier; they retain their MVC status.
        mvc.perform(get("/__test/non-api").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
            .andExpect(status().isNotFound());
    }

    @Test
    void bootRegisteredHttpSecurityBindsAuthenticatedAndAnonymousOperations() throws Exception {
        mvc.perform(get("/api/__test/identity/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(ALICE.toString()));

        mvc.perform(get("/api/__test/identity/optional"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("anonymous"));

        mvc.perform(get("/api/__test/identity/protected"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void apiRootIsCoveredAndHttpBearerRejectsOtherwiseValidOversizedTokens() throws Exception {
        // /api is matched by the enclosing /api/** security chain, not only /api/ descendants.
        mvc.perform(get("/api").header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));

        JwtCredentialProfile largeProfile = new JwtCredentialProfile(PROFILE.name(), PROFILE.issuer(),
            PROFILE.tokenType(), PROFILE.audiences(), PROFILE.kind(), PROFILE.trustedPrimaryProfiles(),
            PROFILE.maximumLifetime(), PROFILE.clockSkew(), 16_384);
        JwtCredentialCodec largeCodec = new JwtCredentialCodec(largeProfile,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")), CLOCK);
        String largeToken = largeCodec.sign(new JwtIssueRequest(ALICE, "session-a", "large-token",
            NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180),
            Optional.empty(), Map.of("padding", "x".repeat(6300))));
        org.junit.jupiter.api.Assertions.assertTrue(largeToken.length() > 8192);
        org.junit.jupiter.api.Assertions.assertTrue(largeToken.length() < 16_384);
        mvc.perform(get("/api/__test/identity/optional")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + largeToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));
    }

    @Test
    void bootRegisteredHttpSecurityRejectsMalformedAndWrongProfileBearersBeforeDispatch() throws Exception {
        mvc.perform(get("/api/__test/identity/optional")
                .header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));

        mvc.perform(get("/api/__test/identity/optional")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token() + "invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));

        mvc.perform(get("/api/__test/identity/optional")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));

        mvc.perform(get("/api/__test/identity/optional")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongProfileToken()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));
    }

    private String token() {
        return iamPrimaryJwtCredentialCodec.sign(new JwtIssueRequest(ALICE, "session-a", "test-token",
            NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
    }

    private String expiredToken() {
        return iamPrimaryJwtCredentialCodec.sign(new JwtIssueRequest(ALICE, "session-a", "expired-token",
            NOW.minusSeconds(240), NOW.minusSeconds(240), NOW.minusSeconds(35), Optional.empty(), Map.of()));
    }

    private String wrongProfileToken() {
        JwtCredentialProfile otherProfile = new JwtCredentialProfile(
            "refresh", PROFILE.issuer(), "sw-refresh+jwt", PROFILE.audiences(),
            JwtCredentialProfile.Kind.PRIMARY, Set.of(), PROFILE.maximumLifetime(), PROFILE.clockSkew(), 4096);
        JwtCredentialCodec otherCodec = new JwtCredentialCodec(otherProfile,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")), CLOCK);
        return otherCodec.sign(new JwtIssueRequest(ALICE, "session-a", "wrong-profile-token",
            NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
    }

    private static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @TestComponent
    @RestController
    static final class ProbeController {
        private final OperationIngressDispatcher ingress;

        ProbeController(OperationIngressDispatcher ingress) {
            this.ingress = ingress;
        }

        @GetMapping("/api/__test/identity/protected")
        Map<String, String> protectedIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.requireUserId().toString()));
        }

        @GetMapping("/api/__test/identity/optional")
        Map<String, String> optionalIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.userId().map(UUID::toString).orElse("anonymous")));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        JwtCredentialCodec iamPrimaryJwtCredentialCodec() {
            return new JwtCredentialCodec(PROFILE,
                new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")), CLOCK);
        }

        @Bean
        ProbeController probeController(OperationIngressDispatcher ingress) {
            return new ProbeController(ingress);
        }
    }
}

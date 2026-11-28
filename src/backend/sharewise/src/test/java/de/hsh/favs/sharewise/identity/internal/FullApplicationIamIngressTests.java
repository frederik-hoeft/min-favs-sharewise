package de.hsh.favs.sharewise.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.identity.internal.web.IamBearerIngressFilter;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.DatabaseFreeContext;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokenConfiguration;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokens;
import jakarta.servlet.Filter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the primary credential path through normal Boot component scanning, MVC and filter registration.
 * Database auto-configuration is excluded so this remains an offline Unit-suite component test.
 */
@ComponentTest
@SpringBootTest(classes = {ShareWiseApplication.class, FullApplicationIamIngressTests.Fixture.class}, properties = {
    DatabaseFreeContext.EXCLUDE_DATABASE_AUTOCONFIGURATION,
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class FullApplicationIamIngressTests {

    private static final Instant NOW = TestAccessTokens.NOW;
    private static final Clock CLOCK = TestAccessTokens.CLOCK;
    private static final UUID ALICE = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID SESSION = UUID.fromString("12345678-1234-4123-8123-1234567890ac");
    private static final JwtCredentialProfile PROFILE = TestAccessTokens.PROFILE;

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired FilterChainProxy securityFilterChain;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMappings;
    // This Unit-suite context excludes DataSource auto-configuration. Isolate the
    // real readiness probe so this test verifies HTTP security, not database wiring.
    @MockitoBean(name = "dataSourceReadinessIndicator", enforceOverride = true)
    ReadinessIndicator dataSourceReadinessIndicator;

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

    @Test
    void distributedAuthorizationFixtureIsNotRegisteredInOrdinaryBootContext() throws Exception {
        assertFalse(handlerMappings.getHandlerMethods().keySet().stream()
            .flatMap(mapping -> mapping.getPatternValues().stream())
            .anyMatch(path -> path.startsWith("/api/__test/distributed-authorization")));
        mvc.perform(post("/api/__test/distributed-authorization/transfer/{source}", ALICE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
            .andExpect(status().isNotFound());
    }

    @Test
    void everyProductionApiControllerDeclaresItsAdmissionContract() {
        for (java.util.Map.Entry<org.springframework.web.servlet.mvc.method.RequestMappingInfo,
                org.springframework.web.method.HandlerMethod> mapping : handlerMappings.getHandlerMethods().entrySet()) {
            org.springframework.web.method.HandlerMethod handler = mapping.getValue();
            // Synthetic handlers are tested separately below; real application
            // controllers in any bounded context must never be unclassified.
            if (handler.getBeanType().getName().contains("Tests$")) {
                continue;
            }
            if (mapping.getKey().getPatternValues().stream().noneMatch(path -> path.startsWith("/api/"))) {
                continue;
            }
            de.hsh.favs.sharewise.shared.infra.web.ApiAccess declaration =
                org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    handler.getMethod(), de.hsh.favs.sharewise.shared.infra.web.ApiAccess.class);
            if (declaration == null) {
                declaration = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    handler.getBeanType(), de.hsh.favs.sharewise.shared.infra.web.ApiAccess.class);
            }
            org.junit.jupiter.api.Assertions.assertNotNull(declaration,
                    () -> "Missing @ApiAccess: " + handler);
            if (declaration.value() == de.hsh.favs.sharewise.shared.infra.web.ApiAccess.Mode.CUSTOM) {
                org.junit.jupiter.api.Assertions.assertFalse(declaration.policy().isBlank());
            } else {
                org.junit.jupiter.api.Assertions.assertTrue(declaration.policy().isBlank());
            }
        }
    }

    @Test
    void explicitlyDeclaredRouteAdmissionDeniesUnclassifiedHandlersAndEnforcesCustomPolicy() throws Exception {
        String bearer = "Bearer " + token();
        // An explicitly anonymous wildcard must not override the exact
        // protected/unclassified MVC mappings, irrespective of map iteration order.
        mvc.perform(get("/api/__test/identity/arbitrary-other-route"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.wildcard").value("true"));
        // A new controller cannot make an endpoint public by omitting @ApiAccess.
        mvc.perform(get("/api/__test/identity/unclassified"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/__test/identity/unclassified")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("access_denied"));
        mvc.perform(get("/api/__test/identity/custom"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/__test/identity/custom")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/__test/identity/custom")
                .header(HttpHeaders.AUTHORIZATION, bearer).header("X-Allowed-Test", "yes"))
            .andExpect(status().isOk());
    }

    @Test
    void applicationApiChainProtectsOtherContextsWithoutSeparateMiddlewareOrCookieCsrf() throws Exception {
        String credential = "Bearer " + token();
        String endpoint = "/api/groups/v1/__test/identity";
        // Group-owned HTTP surfaces inherit IAM bearer verification from the one API chain.
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, credential))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(ALICE.toString()));
        mvc.perform(get(endpoint))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).cookie(new jakarta.servlet.http.Cookie(
                    "__Host-sharewise-refresh", "opaque-refresh-credential")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("authentication_required"));
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));
        // Cookie CSRF/Origin requirements apply to IAM auth mutations, not bearer-only Group POSTs.
        mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, credential))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(ALICE.toString()));
        mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));
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
        JwtCredentialCodec largeCodec = TestAccessTokens.codec(largeProfile, CLOCK);
        String largeToken = largeCodec.sign(new JwtIssueRequest(ALICE, SESSION.toString(), "large-token",
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
        return TestAccessTokens.accessToken(ALICE, SESSION.toString());
    }

    private String expiredToken() {
        return TestAccessTokens.expiredAccessToken(ALICE);
    }

    private String wrongProfileToken() {
        return TestAccessTokens.wrongProfileAccessToken(ALICE);
    }

    @TestComponent
    @RestController
    static final class ProbeController {
        private final OperationIngressDispatcher ingress;

        ProbeController(OperationIngressDispatcher ingress) {
            this.ingress = ingress;
        }

        @ApiAccess(ApiAccess.Mode.ANONYMOUS)
        @GetMapping("/api/__test/identity/{*rest}")
        Map<String, String> permissiveWildcardMustNotShadowExactRoutes() {
            return Map.of("wildcard", "true");
        }

        @ApiAccess
        @GetMapping("/api/__test/identity/protected")
        Map<String, String> protectedIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.requireUserId().toString()));
        }

        @GetMapping("/api/__test/identity/unclassified")
        Map<String, String> unclassified() {
            return Map.of("shouldNeverBeReachable", "true");
        }

        @ApiAccess(value = ApiAccess.Mode.CUSTOM, policy = "test-header")
        @GetMapping("/api/__test/identity/custom")
        Map<String, String> custom() {
            return Map.of("accepted", "true");
        }

        @ApiAccess(ApiAccess.Mode.ANONYMOUS)
        @GetMapping("/api/__test/identity/optional")
        Map<String, String> optionalIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.userId().map(UUID::toString).orElse("anonymous")));
        }
    }

    @TestComponent
    @RestController
    static final class CrossContextProbeController {
        private final OperationIngressDispatcher ingress;

        CrossContextProbeController(OperationIngressDispatcher ingress) {
            this.ingress = ingress;
        }

        @ApiAccess
        @GetMapping("/api/groups/v1/__test/identity")
        Map<String, String> getIdentity() {
            return ingress.dispatch(IdentityContext.class,
                identity -> Map.of("userId", identity.requireUserId().toString()));
        }

        @ApiAccess
        @PostMapping("/api/groups/v1/__test/identity")
        Map<String, String> postIdentity() {
            return getIdentity();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestAccessTokenConfiguration.class)
    static class Fixture {
        @Bean
        de.hsh.favs.sharewise.shared.infra.web.ApiCustomAccessPolicy testHeaderPolicy() {
            return new de.hsh.favs.sharewise.shared.infra.web.ApiCustomAccessPolicy() {
                @Override public String name() { return "test-header"; }
                @Override public boolean allows(jakarta.servlet.http.HttpServletRequest request) {
                    return "yes".equals(request.getHeader("X-Allowed-Test"));
                }
            };
        }
        @Bean
        CrossContextProbeController crossContextProbeController(OperationIngressDispatcher ingress) {
            return new CrossContextProbeController(ingress);
        }

        @Bean
        ProbeController probeController(OperationIngressDispatcher ingress) {
            return new ProbeController(ingress);
        }
    }
}

package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.identity.internal.auth.AuthenticationResult;
import de.hsh.favs.sharewise.identity.internal.auth.RegistrationService;
import de.hsh.favs.sharewise.identity.internal.auth.LoginService;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP-only security contract: real Spring filters/MVC, no PostgreSQL or fake operation commits. */
@ComponentTest
@SpringBootTest(classes = {ShareWiseApplication.class, IamAuthenticationHttpTests.Fixture.class}, properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration,"
        // The event publication registry is database-backed infrastructure (P2.4a).
        + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration,"
        + "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration",
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class IamAuthenticationHttpTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REGISTER = "/api/iam/v1/auth/register";
    private static final String LOGIN = "/api/iam/v1/auth/login";
    private static final String CSRF = "/api/iam/v1/auth/csrf";
    private static final String BODY = "{\"email\":\"user@example.com\",\"password\":\"strong-password\"}";

    @Autowired MockMvc mvc;
    @MockitoBean(name = "httpOperationIngressDispatcher", enforceOverride = true)
    OperationIngressDispatcher ingress;

    @Test
    void csrfBootstrapIsMaskedAndCookieIsHostOnlyHttpOnlySecure() throws Exception {
        MvcResult result = csrfBootstrap();
        Cookie cookie = requireCookie(result);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertTrue("/".equals(cookie.getPath()));
        assertTrue(cookie.getDomain() == null);
        assertTrue("Strict".equals(cookie.getAttribute("SameSite")));
        String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        // XorCsrfTokenRequestAttributeHandler masks the wire token; it is not the raw cookie secret.
        assertTrue(!token.equals(cookie.getValue()));
        mvc.perform(get("/api/iam/v1/csrf").with(secureRequest()))
            .andExpect(status().isNotFound());
        verifyNoInteractions(ingress);
    }

    @Test
    void rejectsMissingCsrfOriginAndMismatchedPairBeforeDispatch() throws Exception {
        mvc.perform(sameOrigin(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(BODY)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        MvcResult bootstrap = csrfBootstrap();
        Cookie cookie = requireCookie(bootstrap);
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        mvc.perform(sameOrigin(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .cookie(cookie).header("X-XSRF-TOKEN", "invalid"))
            .andExpect(status().isForbidden());
        mvc.perform(post(REGISTER).with(secureRequest()).contentType(MediaType.APPLICATION_JSON)
                    .content(BODY).cookie(cookie).header("X-XSRF-TOKEN", token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("invalid_origin"));
        mvc.perform(post(REGISTER).with(secureRequest()).header(HttpHeaders.ORIGIN, "https://attacker.example")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)
                    .cookie(cookie).header("X-XSRF-TOKEN", token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("invalid_origin"));
        verifyNoInteractions(ingress);
    }

    @Test
    void badBearerFailsClosedBeforeAnonymousCredentialEntryAndFutureIamPostsRemainProtected() throws Exception {
        mvc.perform(sameOrigin(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));
        mvc.perform(sameOrigin(post("/api/iam/v1/auth/future-mutation")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        // CSRF protects any unsafe method in the auth namespace, not just POST.
        mvc.perform(sameOrigin(patch("/api/iam/v1/auth/future-mutation")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        // Other IAM APIs are bearer-only and must not inherit cookie CSRF enforcement.
        mvc.perform(sameOrigin(post("/api/iam/v1/non-auth-mutation")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isNotFound());
        verifyNoInteractions(ingress);
    }

    @Test
    void validPairReachesRegistrationAndReturnsBoundedSecureRefreshCookie() throws Exception {
        when(ingress.dispatch(eq(RegistrationService.class), any())).thenReturn(Result.ok(success()));
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        MvcResult accepted = mvc.perform(sameOrigin(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .cookie(requireCookie(bootstrap)).header("X-XSRF-TOKEN", token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").value("signed-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
        // Check actual wire headers, not getCookie(): the latter can retain servlet
        // cookies even when ResponseEntity overwrites their Set-Cookie headers.
        List<String> setCookies = accepted.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, setCookies.size(), "Expected CSRF deletion and refresh cookies");
        String clearedCsrf = setCookies.stream()
                .filter(value -> value.startsWith("__Host-sharewise-csrf="))
                .findFirst().orElseThrow();
        String refresh = setCookies.stream()
                .filter(value -> value.startsWith("__Host-sharewise-refresh="))
                .findFirst().orElseThrow();
        assertTrue(clearedCsrf.contains("Max-Age=0"));
        assertTrue(refresh.contains("HttpOnly"));
        assertTrue(refresh.contains("Secure"));
        assertTrue(refresh.contains("SameSite=Strict"));
        assertTrue(refresh.contains("Path=/"));
        assertFalse(refresh.contains("Domain="));
        assertTrue(refresh.contains("Max-Age=604800"));
    }

    @Test
    void requestValidationAndGenericLoginDenialNeverEmitRefreshCredentials() throws Exception {
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        Cookie cookie = requireCookie(bootstrap);
        mvc.perform(sameOrigin(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"invalid\",\"password\":\"123\"}"))
                    .cookie(cookie).header("X-XSRF-TOKEN", token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"))
            .andExpect(jsonPath("$.fieldErrors.length()").value(2))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("invalid"))
            .andExpect(jsonPath("$.fieldErrors[1].field").value("password"))
            .andExpect(jsonPath("$.fieldErrors[1].code").value("invalid"));
        mvc.perform(sameOrigin(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"invalid\",\"password\":\"\"}"))
                    .cookie(cookie).header("X-XSRF-TOKEN", token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        verifyNoInteractions(ingress);
        when(ingress.dispatch(eq(LoginService.class), any()))
                .thenReturn(Result.err(de.hsh.favs.sharewise.identity.internal.auth.LoginFailure.INVALID_CREDENTIALS));
        mvc.perform(sameOrigin(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .cookie(cookie).header("X-XSRF-TOKEN", token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credentials"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private MvcResult csrfBootstrap() throws Exception {
        return mvc.perform(get(CSRF).with(secureRequest()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    }

    private static Cookie requireCookie(MvcResult bootstrap) {
        Cookie cookie = bootstrap.getResponse().getCookie("__Host-sharewise-csrf");
        assertNotNull(cookie);
        return cookie;
    }

    private static MockHttpServletRequestBuilder sameOrigin(MockHttpServletRequestBuilder builder) {
        return builder.with(secureRequest()).header(HttpHeaders.ORIGIN, "https://localhost");
    }

    private static RequestPostProcessor secureRequest() {
        return request -> {
            request.setScheme("https");
            request.setSecure(true);
            request.setServerPort(443);
            request.setServerName("localhost");
            return request;
        };
    }

    private static AuthenticationResult success() {
        RefreshCredentialGenerator.GeneratedCredential refresh = new RefreshCredentialGenerator()
                .generate(UUID.randomUUID());
        return new AuthenticationResult(UUID.randomUUID(), UUID.randomUUID(),
                new IssuedAccessToken("signed-token", NOW.plusSeconds(300)), refresh,
                NOW.plus(Duration.ofDays(7)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        IamAuthenticationController controller(OperationIngressDispatcher ingress,
                CsrfTokenRepository repository) {
            PasswordPolicy policy = PasswordPolicy.initial();
            return new IamAuthenticationController(ingress, new RegistrationCommandFactory(policy),
                    new LoginCommandFactory(policy), repository, CLOCK);
        }

        @Bean
        JwtCredentialCodec iamPrimaryJwtCredentialCodec() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            JwtCredentialProfile profile = new JwtCredentialProfile("iam-access", "https://iam.sharewise.test",
                    "sw-iam-access+jwt", Set.of("sharewise-api"), JwtCredentialProfile.Kind.PRIMARY,
                    Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
            return new JwtCredentialCodec(profile,
                    new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", pair)), Optional.of("test")), CLOCK);
        }
    }
}

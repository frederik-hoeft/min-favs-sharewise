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
import de.hsh.favs.sharewise.identity.internal.auth.LogoutStatus;
import de.hsh.favs.sharewise.identity.internal.auth.SessionLifecycleFailure;
import de.hsh.favs.sharewise.identity.internal.auth.SessionLifecycleService;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.DatabaseFreeContext;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokenConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
    DatabaseFreeContext.EXCLUDE_DATABASE_AUTOCONFIGURATION,
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class IamAuthenticationHttpTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REGISTER = "/api/iam/v1/auth/register";
    private static final String LOGIN = "/api/iam/v1/auth/login";
    private static final String CSRF = "/api/iam/v1/auth/csrf";
    private static final String REFRESH = "/api/iam/v1/auth/refresh";
    private static final String LOGOUT = "/api/iam/v1/auth/logout";
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

    @Test
    void refreshReturnsBoundSessionAndRotatedCookieAfterSuccessfulDispatch() throws Exception {
        AuthenticationResult issued = success();
        when(ingress.dispatch(eq(SessionLifecycleService.class), any())).thenReturn(Result.ok(issued));
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        MvcResult accepted = mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedSessionId\":\"" + issued.sessionId() + "\"}")
                    .cookie(requireCookie(bootstrap), new Cookie("__Host-sharewise-refresh", "presented"))
                    .header("X-XSRF-TOKEN", token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(issued.sessionId().toString()))
            .andExpect(jsonPath("$.accessToken").value("signed-token"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
        List<String> cookies = accepted.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith("__Host-sharewise-csrf=")
                && value.contains("Max-Age=0")));
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith("__Host-sharewise-refresh=")
                && value.contains("HttpOnly") && value.contains("SameSite=Strict")));
        assertFalse(accepted.getResponse().getContentAsString().contains(issued.refreshCredential().value()));
    }

    @Test
    void postCommitExpiryRoundingNeverLosesTheReplacementCookie() throws Exception {
        // The service required a viable lifetime at its decision point, but response
        // rendering can race with the same deadline after the transaction committed.
        AuthenticationResult issued = success();
        for (Instant deadline : new Instant[] {NOW.plusMillis(500), NOW.minusMillis(500)}) {
            AuthenticationResult nearDeadline = new AuthenticationResult(issued.userId(),
                    issued.sessionId(), new IssuedAccessToken("signed-token", deadline),
                    issued.refreshCredential(), deadline);
            when(ingress.dispatch(eq(SessionLifecycleService.class), any())).thenReturn(Result.ok(nearDeadline));
            MvcResult bootstrap = csrfBootstrap();
            String csrf = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
            MvcResult response = mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(requireCookie(bootstrap), new Cookie("__Host-sharewise-refresh", "presented"))
                        .header("X-XSRF-TOKEN", csrf)))
                .andExpect(status().isOk())
                .andReturn();
            assertTrue(response.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                    .anyMatch(cookie -> cookie.startsWith("__Host-sharewise-refresh=")
                            && cookie.contains("Max-Age=1")));
        }
    }

    @Test
    void invalidRefreshAndContextMismatchNeverChangeCookies() throws Exception {
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        when(ingress.dispatch(eq(SessionLifecycleService.class), any()))
                .thenReturn(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE));
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content("{}")
                    .cookie(requireCookie(bootstrap)).header("X-XSRF-TOKEN", token)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("refresh_unavailable"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        when(ingress.dispatch(eq(SessionLifecycleService.class), any()))
                .thenReturn(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED));
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedSessionId\":\"" + UUID.randomUUID() + "\"}")
                    .cookie(requireCookie(bootstrap)).header("X-XSRF-TOKEN", token)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("session_context_changed"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void logoutClearsBothCookiesOnlyOnSuccessfulOutcome() throws Exception {
        when(ingress.dispatch(eq(SessionLifecycleService.class), any())).thenReturn(Result.ok(LogoutStatus.NO_SESSION));
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        MvcResult accepted = mvc.perform(sameOrigin(post(LOGOUT).contentType(MediaType.APPLICATION_JSON)
                    .content("{}").cookie(requireCookie(bootstrap)).header("X-XSRF-TOKEN", token)))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
        List<String> cookies = accepted.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith("__Host-sharewise-csrf=")
                && value.contains("Max-Age=0")));
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith("__Host-sharewise-refresh=;")
                && value.contains("Max-Age=0") && value.contains("Secure")
                && value.contains("HttpOnly") && value.contains("SameSite=Strict")
                && value.contains("Path=/")));
    }

    @Test
    void mismatchedLogoutPreservesTheOtherSessionCookie() throws Exception {
        when(ingress.dispatch(eq(SessionLifecycleService.class), any()))
                .thenReturn(Result.err(SessionLifecycleFailure.SESSION_CONTEXT_CHANGED));
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        mvc.perform(sameOrigin(post(LOGOUT).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedSessionId\":\"" + UUID.randomUUID() + "\"}")
                    .cookie(requireCookie(bootstrap), new Cookie("__Host-sharewise-refresh", "presented"))
                    .header("X-XSRF-TOKEN", token)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("session_context_changed"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void failedLifecycleDispatchCannotExposeAnUncommittedRefreshCookie() throws Exception {
        when(ingress.dispatch(eq(SessionLifecycleService.class), any()))
                .thenThrow(new IllegalStateException("Simulated transaction failure"));
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content("{}")
                    .cookie(requireCookie(bootstrap), new Cookie("__Host-sharewise-refresh", "presented"))
                    .header("X-XSRF-TOKEN", token)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("internal_error"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void refreshForwardsTheBoundSessionAndRejectsAmbiguousDuplicateCookies() throws Exception {
        UUID expected = UUID.randomUUID();
        SessionLifecycleService service = org.mockito.Mockito.mock(SessionLifecycleService.class);
        when(service.refresh("presented", expected))
                .thenReturn(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE));
        when(service.refresh(org.mockito.ArgumentMatchers.isNull(), eq(expected)))
                .thenReturn(Result.err(SessionLifecycleFailure.REFRESH_UNAVAILABLE));
        when(ingress.dispatch(eq(SessionLifecycleService.class), any())).thenAnswer(invocation -> {
            java.util.function.Function<SessionLifecycleService, ?> operation = invocation.getArgument(1);
            return operation.apply(service);
        });
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        Cookie csrf = requireCookie(bootstrap);
        String body = "{\"expectedSessionId\":\"" + expected + "\"}";
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(body)
                    .cookie(csrf, new Cookie("__Host-sharewise-refresh", "presented"))
                    .header("X-XSRF-TOKEN", token)))
            .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(service).refresh("presented", expected);
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(body)
                    .cookie(csrf, new Cookie("__Host-sharewise-refresh", "first"),
                            new Cookie("__Host-sharewise-refresh", "second"))
                    .header("X-XSRF-TOKEN", token)))
            .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(service).refresh(null, expected);
    }

    @Test
    void malformedSessionBindingAndMissingCsrfOrOriginNeverDispatchLifecycle() throws Exception {
        MvcResult bootstrap = csrfBootstrap();
        String token = com.jayway.jsonpath.JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        Cookie csrf = requireCookie(bootstrap);
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedSessionId\":\"bad\"}")
                    .cookie(csrf).header("X-XSRF-TOKEN", token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(sameOrigin(post(LOGOUT).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedSessionId\":\"bad\"}")
                    .cookie(csrf).header("X-XSRF-TOKEN", token)))
            .andExpect(status().isBadRequest());
        mvc.perform(sameOrigin(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content("{}")
                    .cookie(csrf)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(post(LOGOUT).with(secureRequest()).contentType(MediaType.APPLICATION_JSON)
                    .content("{}").cookie(csrf).header("X-XSRF-TOKEN", token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("invalid_origin"));
        verifyNoInteractions(ingress);
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
        ApplicationTokenGenerator.GeneratedToken refresh = new ApplicationTokenGenerator()
                .generate(UUID.randomUUID());
        return new AuthenticationResult(UUID.randomUUID(), UUID.randomUUID(),
                new IssuedAccessToken("signed-token", NOW.plusSeconds(300)), refresh,
                NOW.plus(Duration.ofDays(7)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestAccessTokenConfiguration.class)
    static class Fixture {
        @Bean
        IamAuthenticationController controller(OperationIngressDispatcher ingress,
                CsrfTokenRepository repository) {
            PasswordPolicy policy = PasswordPolicy.initial();
            return new IamAuthenticationController(ingress, new RegistrationCommandFactory(policy),
                    new LoginCommandFactory(policy), repository, CLOCK);
        }
    }
}

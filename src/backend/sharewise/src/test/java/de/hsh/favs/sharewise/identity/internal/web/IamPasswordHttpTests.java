package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.identity.internal.auth.PasswordChangeFailure;
import de.hsh.favs.sharewise.identity.internal.auth.PasswordChangeService;
import de.hsh.favs.sharewise.identity.internal.auth.PasswordChangeStatus;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.web.dto.ChangePasswordRequest;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
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
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Password transport via the real bearer, CSRF, Origin and method-authorization chain. */
@ComponentTest
@SpringBootTest(classes = {ShareWiseApplication.class, IamPasswordHttpTests.Fixture.class}, properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration",
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class IamPasswordHttpTests {

    private static final String PASSWORD = "/api/iam/v1/auth/password";
    private static final String BODY = "{\"currentPassword\":\"old-secret-123\",\"newPassword\":\"new-secret-456\"}";
    private static final UUID USER = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID SESSION = UUID.fromString("12345678-1234-4123-8123-1234567890cd");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JwtCredentialCodec iamPrimaryJwtCredentialCodec;
    @MockitoBean(name = "httpOperationIngressDispatcher", enforceOverride = true)
    OperationIngressDispatcher ingress;

    @Test
    void requiresValidBearerButNotCsrfOrOriginAndNeverIssuesCredentials() throws Exception {
        mvc.perform(post(PASSWORD).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .cookie(new Cookie("__Host-sharewise-refresh", "cookie-only")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("authentication_required"));
        mvc.perform(post(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_credential"));
        verifyNoInteractions(ingress);

        when(ingress.dispatch(eq(PasswordChangeService.class), any()))
                .thenReturn(Result.ok(PasswordChangeStatus.CHANGED));
        mvc.perform(post(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
            .andExpect(result -> assertTrue(result.getResponse().getContentAsString().isEmpty()));
    }

    @Test
    void rejectsWrongIssuerAudienceAndTypeEvenWithSignedCredentials() throws Exception {
        for (JwtCredentialProfile profile : List.of(
                profile("https://not-iam.test", "sw-iam-access+jwt", "sw-api-v1"),
                profile("https://iam.sharewise.test", "sw-wrong+jwt", "sw-api-v1"),
                profile("https://iam.sharewise.test", "sw-iam-access+jwt", "wrong-audience"))) {
            mvc.perform(post(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(profile))
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credential"));
        }
        verifyNoInteractions(ingress);
    }

    @Test
    void validatesThroughDomainFactoryAndRejectsMalformedTransport() throws Exception {
        for (String body : new String[]{
                "{}", "{\"currentPassword\":\"old-secret-123\"}",
                "{\"newPassword\":\"new-secret-456\"}",
                "{\"currentPassword\":7,\"newPassword\":\"new-secret-456\"}",
                "{\"currentPassword\":\"old-secret-123\",\"newPassword\":false}",
                "{\"currentPassword\":\"old-secret-123\",\"newPassword\":\"new-secret-456\",\"userId\":\"x\"}"}) {
            mvc.perform(post(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
        for (String body : new String[]{
                "{\"currentPassword\":null,\"newPassword\":\"new-secret-456\"}",
                "{\"currentPassword\":\"old-secret-123\",\"newPassword\":\"short\"}",
                "{\"currentPassword\":\"same-password\",\"newPassword\":\"same-password\"}"}) {
            mvc.perform(post(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
        verifyNoInteractions(ingress);
    }

    @Test
    void mapsCommittedDenialsWithoutLeaksOrRefreshableBearerChallenge() throws Exception {
        for (PasswordChangeFailure failure : PasswordChangeFailure.values()) {
            when(ingress.dispatch(eq(PasswordChangeService.class), any())).thenReturn(Result.err(failure));
            int expectedStatus = switch (failure) {
                case CURRENT_PASSWORD_INVALID -> 400;
                case CREDENTIAL_CHANGED -> 409;
                case ACCOUNT_UNAVAILABLE -> 403;
                case SESSION_UNAVAILABLE -> 401;
            };
            String code = switch (failure) {
                case CURRENT_PASSWORD_INVALID -> "current_password_invalid";
                case CREDENTIAL_CHANGED -> "credential_changed";
                case ACCOUNT_UNAVAILABLE -> "account_unavailable";
                case SESSION_UNAVAILABLE -> "session_unavailable";
            };
            mvc.perform(post(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    assertFalse(response.contains("old-secret-123"));
                    assertFalse(response.contains("new-secret-456"));
                });
        }
    }

    @Test
    void csrfAndOriginRemainFailClosedForAllOtherAuthMutations() throws Exception {
        // Only the exact POST method/path is exempt: future routes and other verbs are protected.
        for (String uri : List.of("/api/iam/v1/auth/future", "/api/iam/v1/auth/password/extra")) {
            mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_origin"));
            mvc.perform(sameOrigin(post(uri)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_invalid"));
        }
        mvc.perform(sameOrigin(patch(PASSWORD)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        for (String path : List.of("/api/iam/v1/auth/register", "/api/iam/v1/auth/login",
                "/api/iam/v1/auth/refresh", "/api/iam/v1/auth/logout")) {
            mvc.perform(sameOrigin(post(path)).contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_invalid"));
        }
        verifyNoInteractions(ingress);
    }

    @Test
    void redactsPasswordRequestInDiagnosticStringRepresentation() {
        ChangePasswordRequest input = new ChangePasswordRequest("old-secret-123", "new-secret-456");
        assertEquals("ChangePasswordRequest[credentials redacted]", input.toString());
        assertFalse(input.toString().contains(input.currentPassword()));
        assertFalse(input.toString().contains(input.newPassword()));
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

    private String bearer() {
        return iamPrimaryJwtCredentialCodec.sign(new JwtIssueRequest(USER, SESSION.toString(),
                "password-http-test", NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180),
                Optional.empty(), Map.of()));
    }

    private static String bearer(JwtCredentialProfile profile) {
        JwtCredentialCodec codec = new JwtCredentialCodec(profile,
                new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", Fixture.KEY)), Optional.of("test")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return codec.sign(new JwtIssueRequest(USER, SESSION.toString(), "password-http-test",
                NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
    }

    private static JwtCredentialProfile profile(String issuer, String type, String audience) {
        return new JwtCredentialProfile("iam-access", issuer, type, Set.of(audience),
                JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5),
                Duration.ofSeconds(30), 4096);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        private static final KeyPair KEY = generateKey();

        private static KeyPair generateKey() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                return generator.generateKeyPair();
            } catch (java.security.GeneralSecurityException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        @Bean
        IamPasswordController passwordController(OperationIngressDispatcher ingress) {
            return new IamPasswordController(ingress,
                    new PasswordChangeCommandFactory(PasswordPolicy.initial()));
        }

        @Bean("iamPrimaryJwtCredentialCodec")
        JwtCredentialCodec codec() {
            return new JwtCredentialCodec(profile("https://iam.sharewise.test",
                    "sw-iam-access+jwt", "sw-api-v1"),
                    new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}

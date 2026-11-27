package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfile;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileFailure;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileService;
import de.hsh.favs.sharewise.identity.internal.profile.ProfileLookupService;
import de.hsh.favs.sharewise.identity.internal.profile.PublicProfile;
import de.hsh.favs.sharewise.identity.internal.web.dto.AuthCredentialsRequest;
import de.hsh.favs.sharewise.identity.internal.web.dto.RegistrationRequest;
import jakarta.servlet.http.Cookie;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
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

/** Account API through production Spring authorization, bearer verification and MVC. */
@ComponentTest
@SpringBootTest(classes = {ShareWiseApplication.class, IamAccountHttpTests.Fixture.class}, properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration",
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class IamAccountHttpTests {

    private static final String ACCOUNT = "/api/iam/v1/account";
    private static final UUID ALICE = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID SESSION = UUID.fromString("12345678-1234-4123-8123-1234567890ac");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JwtCredentialCodec iamPrimaryJwtCredentialCodec;
    @MockitoBean(name = "httpOperationIngressDispatcher", enforceOverride = true)
    OperationIngressDispatcher ingress;

    @Test
    void requiresValidPrimaryBearerForGetAndPatchWithoutCsrf() throws Exception {
        mvc.perform(get(ACCOUNT)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication_required"));
        mvc.perform(patch(ACCOUNT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":null,\"clear\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credential"));
        for (JwtCredentialProfile profile : List.of(
                new JwtCredentialProfile("iam-access", "https://wrong.issuer.test", "sw-iam-access+jwt",
                        Set.of("sw-api-v1"), JwtCredentialProfile.Kind.PRIMARY,
                        Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096),
                new JwtCredentialProfile("iam-access", "https://iam.sharewise.test", "sw-wrong+jwt",
                        Set.of("sw-api-v1"), JwtCredentialProfile.Kind.PRIMARY,
                        Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096),
                new JwtCredentialProfile("iam-access", "https://iam.sharewise.test", "sw-iam-access+jwt",
                        Set.of("wrong-audience"), JwtCredentialProfile.Kind.PRIMARY,
                        Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096))) {
            mvc.perform(get(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(profile)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid_credential"));
        }
        verifyNoInteractions(ingress);
    }

    @Test
    void returnsNarrowUncacheableAccountProjectionAndNoSecrets() throws Exception {
        when(ingress.dispatch(eq(AccountProfileService.class), any()))
                .thenReturn(Result.ok(new AccountProfile(ALICE, "alice@example.org", "Alice")));
        mvc.perform(get(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.userId").value(ALICE.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.org"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.sessions").doesNotExist());
    }

    @Test
    void patchRejectsInvalidNames() throws Exception {
        for (String body : new String[]{"{\"displayName\":null}",
                "{\"displayName\":\" \"}", "{\"displayName\":\"\\u2028\"}",
                "{\"displayName\":\"\\u200b\"}", "{\"displayName\":\"Alice\\u202e\"}"}) {
            mvc.perform(patch(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        }
        verifyNoInteractions(ingress);
        when(ingress.dispatch(eq(AccountProfileService.class), any()))
                .thenReturn(Result.ok(new AccountProfile(ALICE, "alice@example.org", "<b>Safe text</b>")));
        mvc.perform(patch(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"<b>Safe text</b>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("<b>Safe text</b>"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void patchRejectsMalformedOrUnsupportedJsonFieldsBeforeDispatch() throws Exception {
        for (String body : new String[]{"{}", "{\"clear\":true}",
                "{\"displayName\":\"Alice\",\"clear\":true}",
                "{\"displayName\":\"Alice\",\"clear\":false}",
                "{\"displayName\":\"Alice\",\"clear\":false,\"email\":\"x\"}",
                "{\"displayName\":4}",
                "{\"displayName\":1.5}",
                "{\"displayName\":false}",
                "{\"displayName\":[]}",
                "{\"displayName\":{}}",
                "{\"displayName\":\"Alice\",\"clear\":\"false\"}",
                "{\"displayName\":\"Alice\",\"clear\":1}",
                "{\"displayName\":\"Alice\",\"clear\":null}"}) {
            mvc.perform(patch(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertEquals(400,
                        result.getResponse().getStatus(), "Unexpected status for: " + body))
                .andExpect(jsonPath("$.code").value("malformed_request"));
        }
        verifyNoInteractions(ingress);
    }

    @Test
    void globalJsonRulesAlsoApplyToCredentialRequestDtos() throws Exception {
        for (String body : new String[]{
                "{\"email\":\"alice@example.org\",\"password\":\"test\",\"extra\":1}",
                "{\"email\":42,\"password\":\"test\"}",
                "{\"email\":\"alice@example.org\",\"password\":false}"}) {
            assertThrows(JacksonException.class,
                    () -> json.readValue(body, AuthCredentialsRequest.class), body);
        }
        assertEquals("alice@example.org",
                json.readValue("{\"email\":\"alice@example.org\",\"password\":\"test\"}",
                        AuthCredentialsRequest.class).email());
    }

    @Test
    void registrationDtoRejectsMalformedNameTypesInIsolation() throws Exception {
        for (String invalid : List.of("4", "1.5", "false", "[]", "{}")) {
            String body = "{\"email\":\"alice@example.org\",\"password\":\"password1\",\"displayName\":" + invalid + "}";
            assertThrows(JacksonException.class, () -> json.readValue(body, RegistrationRequest.class), body);
        }
        assertEquals("Alice", json.readValue("{\"email\":\"alice@example.org\",\"password\":\"password1\",\"displayName\":\"Alice\"}",
                RegistrationRequest.class).displayName());
    }

    @Test
    void disabledOrMissingAccountGetsTypedDenial() throws Exception {
        when(ingress.dispatch(eq(AccountProfileService.class), any()))
                .thenReturn(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE));
        mvc.perform(get(ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_unavailable"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void lookupDtoRejectsMalformedElementTypesInIsolation() throws Exception {
        for (String invalid : List.of("4", "1.5", "false", "[]", "{}")) {
            String body = "{\"userIds\":[" + invalid + "]}";
            assertThrows(JacksonException.class, () -> json.readValue(body, IamProfilesController.LookupRequest.class), body);
        }
    }

    @Test
    void lookupRequiresPrimaryAndReturnsOnlyPublicFields() throws Exception {
        String path = "/api/iam/v1/profiles/lookup";
        String body = "{\"userIds\":[\"" + ALICE + "\"]}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).cookie(new Cookie("__Host-sharewise-refresh", "cookie-only"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        for (String invalid : List.of("{}", "{\"userIds\":null}", "{\"userIds\":[null]}", "{\"userIds\":[\"bad\"]}",
                "{\"userIds\":[4]}", "{\"userIds\":[false]}", "{\"userIds\":[[]]}", "{\"userIds\":[{}]}",
                "{\"userIds\":[" + String.join(",", java.util.Collections.nCopies(101, "\"" + ALICE + "\"")) + "]}")) {
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(ingress);
        when(ingress.dispatch(eq(ProfileLookupService.class), any())).thenReturn(List.of(new PublicProfile(ALICE, "Alice")));
        String boundary = "{\"userIds\":[" + String.join(",", java.util.Collections.nCopies(100, "\"" + ALICE + "\"")) + "]}";
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON).content(boundary))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.profiles[0].userId").value(ALICE.toString()))
                .andExpect(jsonPath("$.profiles[0].displayName").value("Alice"))
                .andExpect(jsonPath("$.profiles[0].email").doesNotExist())
                .andExpect(jsonPath("$.profiles[0].disabledAt").doesNotExist());
    }

    private String bearer() {
        return iamPrimaryJwtCredentialCodec.sign(new JwtIssueRequest(ALICE, SESSION.toString(), "test-token",
                NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
    }

    private String bearer(JwtCredentialProfile profile) {
        JwtCredentialCodec alternative = new JwtCredentialCodec(profile,
                new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", Fixture.KEY)), Optional.of("test")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return alternative.sign(new JwtIssueRequest(ALICE, SESSION.toString(), "test-token", NOW.minusSeconds(1),
                NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
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
        IamAccountController accountController(OperationIngressDispatcher ingress) {
            return new IamAccountController(ingress, new AccountProfileUpdateFactory());
        }

        @Bean
        IamProfilesController profilesController(OperationIngressDispatcher ingress) {
            return new IamProfilesController(ingress);
        }

        @Bean("iamPrimaryJwtCredentialCodec")
        JwtCredentialCodec codec() throws Exception {
            JwtCredentialProfile profile = new JwtCredentialProfile("iam-access", "https://iam.sharewise.test",
                    "sw-iam-access+jwt", Set.of("sw-api-v1"), JwtCredentialProfile.Kind.PRIMARY,
                    Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
            return new JwtCredentialCodec(profile,
                    new Es256JwtSignatureService(List.of(JwtEcKey.signing("test", KEY)), Optional.of("test")),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}

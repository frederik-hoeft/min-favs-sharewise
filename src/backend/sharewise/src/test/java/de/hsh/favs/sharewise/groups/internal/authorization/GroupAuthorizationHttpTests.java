package de.hsh.favs.sharewise.groups.internal.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.groups.GroupAuthorizationContext;
import de.hsh.favs.sharewise.groups.GroupAuthorizationFailure;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.groups.GroupAuthorizationPort;
import de.hsh.favs.sharewise.groups.GroupAuthorizationVerificationFailure;
import de.hsh.favs.sharewise.groups.VerifiedGroupMembership;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.testsupport.configuration.DatabaseFreeContext;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MvcResult;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationAffinityPolicy;
import de.hsh.favs.sharewise.shared.infra.web.AttestationChallenge;
import de.hsh.favs.sharewise.shared.infra.web.AttestationProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationTransport;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokenConfiguration;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Full production ingress/correlation and semantic Group facade, with independent test keys and a test-only consumer. */
@ComponentTest
@SpringBootTest(classes = {ShareWiseApplication.class, GroupAuthorizationHttpTests.Fixture.class, GroupAuthorizationHttpTests.MembershipFixture.class}, properties = {
    DatabaseFreeContext.EXCLUDE_DATABASE_AUTOCONFIGURATION,
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class GroupAuthorizationHttpTests {
    private static final UUID USER = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID SESSION = TestAccessTokens.sessionFor(USER);
    private static final UUID X = UUID.fromString("40000000-4000-7000-8000-000000000001");
    private static final UUID Y = UUID.fromString("40000000-4000-7000-8000-000000000002");
    private static final UUID Z = UUID.fromString("40000000-4000-7000-8000-000000000003");
    private static final String ISSUER = "https://groups.sharewise.test";
    private static final String AUDIENCE = "sw-api-v1";
    private static final String ENDPOINT = "/api/__test/distributed-authorization/transfer/{source}";
    private static final String HEADER = AttestationTransport.HEADER;
    private static final KeyPair GROUP_KEY = newKey();
    private static final String KEY_ID = "groups-test";
    private static final Path GROUP_PUBLIC = pem(GROUP_KEY, false);
    private static final Path GROUP_PRIVATE = pem(GROUP_KEY, true);
    private static final JwtCredentialProfile PROFILE = GroupMembershipTrust.profile(
            ISSUER, AUDIENCE, TestAccessTokens.PROFILE.issuer(), Duration.ofSeconds(15));
    private static final JwtCredentialCodec SIGNER = signer(PROFILE, GROUP_KEY);

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        registry.add("sharewise.groups.attestation.enabled", () -> "true");
        registry.add("sharewise.groups.attestation.issuer", () -> ISSUER);
        registry.add("sharewise.groups.attestation.trusted-primary-issuer", TestAccessTokens.PROFILE::issuer);
        registry.add("sharewise.groups.attestation.keys[0].id", () -> KEY_ID);
        registry.add("sharewise.groups.attestation.keys[0].public-key-file", GROUP_PUBLIC::toString);
        registry.add("sharewise.groups.attestation.active-key-id", () -> KEY_ID);
        registry.add("sharewise.groups.attestation.keys[0].private-key-file", GROUP_PRIVATE::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired OperationDispatcher dispatcher;
    @Autowired Effects effects;
    @MockitoBean(name = "dataSourceReadinessIndicator", enforceOverride = true)
    ReadinessIndicator readiness;

    @Test
    void challengeThenSecondRequirementThenExactlyOneProtectedEffect() throws Exception {
        int before = effects.count.get();
        mvc.perform(transfer())
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("attestation_required"))
            .andExpect(jsonPath("$.detail").value("Group membership evidence is required."))
            .andExpect(jsonPath("$.authorization.requirementId").value("source-membership"))
            .andExpect(jsonPath("$.authorization.credential.authority").value("groups"))
            .andExpect(jsonPath("$.authorization.credential.kind").value("membership"))
            .andExpect(jsonPath("$.authorization.credential.scope").value(X.toString()))
            .andExpect(jsonPath("$.authorization.cache.policy").value("path_affine"));
        assertEquals(before, effects.count.get());
        String source = token(X, GroupRole.ADMIN);
        mvc.perform(transfer().header(HEADER, source))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.authorization.requirementId").value("destination-membership"))
            .andExpect(jsonPath("$.authorization.credential.scope").value(Y.toString()))
            .andExpect(jsonPath("$.authorization.cache.policy").value("query_affine"))
            .andExpect(jsonPath("$.authorization.cache.parameters[0]").value("destination"));
        assertEquals(before, effects.count.get());
        // Repeated header lines and ordering do not change resource/role pairing.
        mvc.perform(transfer().header(HEADER, token(Y, GroupRole.MEMBER), "\t" + source + " "))
            .andExpect(status().isOk());
        assertEquals(before + 1, effects.count.get());
    }

    @Test
    void memberCannotBorrowAdminFromAnotherResourceAndUnrelatedEvidenceDoesNotGrantAccess() throws Exception {
        int before = effects.count.get();
        mvc.perform(transfer().header(HEADER, token(X, GroupRole.MEMBER) + ", " + token(Y, GroupRole.ADMIN)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("insufficient_permissions"))
            .andExpect(jsonPath("$.authorization").doesNotExist());
        mvc.perform(transfer().header(HEADER, token(Z, GroupRole.ADMIN)))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.authorization.credential.scope").value(X.toString()));
        assertEquals(before, effects.count.get());
        mvc.perform(get("/api/__test/distributed-authorization/role/{group}", X)
                .header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER)).header(HEADER, token(X, GroupRole.ADMIN)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.member").value(true)).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(get("/api/__test/distributed-authorization/role/{group}", Z)
                .header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER)).header(HEADER, token(X, GroupRole.ADMIN)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("absent")).andExpect(jsonPath("$.member").value(false));
        dispatcher.dispatch(GroupAuthorizationContext.class, context -> {
            assertTrue(context.role(X).isEmpty());
            assertFalse(context.isMember(X));
            assertEquals(Result.err(new GroupAuthorizationFailure.Unauthenticated()), context.requireMember(X));
            return null;
        });
    }

    @Test
    void conflictsAndMalformedDomainAssertionsNeverPublishOrExecute() throws Exception {
        int before = effects.count.get();
        String good = token(X, GroupRole.ADMIN);
        mvc.perform(transfer().header(HEADER, good, token(X, GroupRole.MEMBER)))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"))
            .andExpect(jsonPath("$.authorization").doesNotExist());
        for (Map<String, String> claims : List.of(Map.of("role", "ADMIN"),
                Map.of("group_id", "bad", "role", "ADMIN"), Map.of("group_id", X.toString(), "role", "admin"),
                Map.of("group_id", X.toString()), Map.of("group_id", "40000000-4000-7000-8000-00000000000A", "role", "ADMIN"))) {
            mvc.perform(transfer().header(HEADER, good, signed(SIGNER, USER, SESSION, claims)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"))
                .andExpect(jsonPath("$.authorization.rejectedIndex").value(1));
        }
        // Even a single-element array is not the scalar encoding promised by this profile.
        for (String invalid : List.of(rawSigned(List.of(X.toString()), "ADMIN", TestAccessTokens.PROFILE.issuer(), Instant.now()),
                rawSigned(X.toString(), List.of("ADMIN"), TestAccessTokens.PROFILE.issuer(), Instant.now()),
                rawSigned(X.toString(), List.of("ADMIN", "MEMBER"), TestAccessTokens.PROFILE.issuer(), Instant.now()),
                rawSigned(X.toString(), 1, TestAccessTokens.PROFILE.issuer(), Instant.now()))) {
            mvc.perform(transfer().header(HEADER, good, invalid))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"))
                .andExpect(jsonPath("$.authorization.rejectedIndex").value(1));
        }
        assertEquals(before, effects.count.get());
    }

    @Test
    void malformedAndDuplicateHeaderListsAreTerminalBeforeEffects() throws Exception {
        int before = effects.count.get();
        String good = token(X, GroupRole.ADMIN);
        List<String> invalid = List.of("", "," + good, good + ",", good + ",," + good, good + "," + good,
            "a.b.c.d", "a b.c.d", "a".repeat(2048) + ".b.c", " ".repeat(8193),
            String.join(",", List.of(good, token(Y, GroupRole.MEMBER), token(Z, GroupRole.ADMIN),
                token(X, GroupRole.ADMIN), token(Y, GroupRole.MEMBER))));
        for (String value : invalid) {
            mvc.perform(transfer().header(HEADER, value))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"))
                .andExpect(jsonPath("$.authorization").doesNotExist());
        }
        mvc.perform(transfer().header(HEADER, good, good))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.authorization").doesNotExist());
        assertEquals(before, effects.count.get());
    }

    @Test
    void untrustedProfileKeyAndBindingFailuresIdentifyOnlyRejectedPosition() throws Exception {
        int before = effects.count.get();
        String good = token(X, GroupRole.ADMIN);
        var claims = Map.of("group_id", Y.toString(), "role", "MEMBER");
        var wrongAudience = GroupMembershipTrust.profile(ISSUER, "sw-api-v1-finance", TestAccessTokens.PROFILE.issuer(), Duration.ofSeconds(15));
        var wrongIssuer = GroupMembershipTrust.profile("https://other.example", AUDIENCE, TestAccessTokens.PROFILE.issuer(), Duration.ofSeconds(15));
        var wrongType = new JwtCredentialProfile(PROFILE.name(), ISSUER, "different+jwt", PROFILE.audiences(),
            PROFILE.kind(), PROFILE.trustedPrimaryProfiles(), PROFILE.maximumLifetime(), PROFILE.clockSkew(), PROFILE.maximumTokenLength());
        List<String> rejected = List.of(signed(signer(wrongAudience, GROUP_KEY), USER, SESSION, claims),
            signed(signer(wrongIssuer, GROUP_KEY), USER, SESSION, claims), signed(signer(wrongType, GROUP_KEY), USER, SESSION, claims),
            signed(signer(PROFILE, newKey()), USER, SESSION, claims),
            signed(TestAccessTokens.codec(PROFILE, KEY_ID, Clock.systemUTC()), USER, SESSION, claims),
            signed(SIGNER, UUID.randomUUID(), SESSION, claims), signed(SIGNER, USER, UUID.randomUUID(), claims),
            rawSigned(Y.toString(), "MEMBER", "https://untrusted-iam.example", Instant.now()),
            rawSigned(Y.toString(), "MEMBER", TestAccessTokens.PROFILE.issuer(), Instant.now().minusSeconds(160)),
            TestAccessTokens.accessToken(USER));
        for (String invalid : rejected) {
            mvc.perform(transfer().header(HEADER, good, invalid))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"))
                .andExpect(jsonPath("$.authorization.rejectedIndex").value(1));
        }
        mvc.perform(post(ENDPOINT, X).param("destination", Y.toString()).header(HEADER, good))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"));
        assertEquals(before, effects.count.get());
    }

    @Test
    void healthAndCookieBootstrapDoNotAcceptSupplementaryEvidence() throws Exception {
        String valid = token(X, GroupRole.ADMIN);
        mvc.perform(get("/api/system/v1/health").header(HEADER, valid))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"));
        for (String path : List.of("/api/iam/v1/auth/csrf", "/api/iam/v1/auth/%63srf")) {
            mvc.perform(get(java.net.URI.create(path)).header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER))
                    .header(HEADER, valid))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
        mvc.perform(get(java.net.URI.create("/api/system/v1/%68ealth"))
                .header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER)).header(HEADER, valid))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"));
    }

    private static MockHttpServletRequestBuilder transfer() {
        return post(ENDPOINT, X).param("destination", Y.toString()).header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER));
    }

    private static String token(UUID group, GroupRole role) {
        return signed(SIGNER, USER, SESSION, Map.of("group_id", group.toString(), "role", role.name()));
    }

    private static String signed(JwtCredentialCodec codec, UUID user, UUID session, Map<String, String> claims) {
        Instant now = Instant.now();
        return codec.sign(new JwtIssueRequest(user, session.toString(), UUID.randomUUID().toString(), now,
            now, now.plusSeconds(120), Optional.of(TestAccessTokens.PROFILE.issuer()), claims));
    }

    /** Sign malformed source-owned assertions deliberately, bypassing the typed issuance request. */
    private static String rawSigned(Object group, Object role, String primaryIssuer, Instant issuedAt) {
        var signature = new Es256JwtSignatureService(List.of(JwtEcKey.signing(KEY_ID, GROUP_KEY)), Optional.of(KEY_ID));
        return signature.sign(PROFILE.tokenType(), JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
            .subject(USER.toString()).id(UUID.randomUUID().toString()).issuedAt(issuedAt).notBefore(issuedAt)
            .expiresAt(issuedAt.plusSeconds(120)).claim("principal_iss", primaryIssuer)
            .claim("principal_sid", SESSION.toString()).claim("group_id", group).claim("role", role).build());
    }

    private static JwtCredentialCodec signer(JwtCredentialProfile profile, KeyPair pair) {
        return new JwtCredentialCodec(profile, new Es256JwtSignatureService(List.of(JwtEcKey.signing(KEY_ID, pair)),
            Optional.of(KEY_ID)), Clock.systemUTC());
    }

    private static KeyPair newKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException("Test key generation failed", failure);
        }
    }

    static Map<String, Object> signingSettings() {
        return Map.of("sharewise.groups.attestation.enabled", "true",
            "sharewise.groups.attestation.issuer", ISSUER,
            "sharewise.groups.attestation.trusted-primary-issuer", TestAccessTokens.PROFILE.issuer(),
            "sharewise.groups.attestation.keys[0].id", KEY_ID,
            "sharewise.groups.attestation.keys[0].public-key-file", GROUP_PUBLIC.toString(),
            "sharewise.groups.attestation.active-key-id", KEY_ID,
            "sharewise.groups.attestation.keys[0].private-key-file", GROUP_PRIVATE.toString());
    }

    private static Path pem(KeyPair key, boolean privateKey) {
        try {
            Path path = Files.createTempFile("group-credential-test-", ".pem");
            path.toFile().deleteOnExit();
            String kind = privateKey ? "PRIVATE KEY" : "PUBLIC KEY";
            byte[] encoded = privateKey ? key.getPrivate().getEncoded() : key.getPublic().getEncoded();
            Files.writeString(path, "-----BEGIN " + kind + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END " + kind + "-----\n");
            return path;
        } catch (Exception failure) {
            throw new IllegalStateException("Test key fixture failed", failure);
        }
    }

    @Test
    void realIssuerSignsLiveRoleAndConsumerAcceptsItWithSameSession() throws Exception {
        int before = effects.count.get();
        String x = acquire(X);
        String y = acquire(Y);
        mvc.perform(transfer().header(HEADER, x, y)).andExpect(status().isOk());
        assertEquals(before + 1, effects.count.get());
        mvc.perform(get("/api/__test/distributed-authorization/role/{group}", X)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestAccessTokens.accessToken(USER, UUID.randomUUID().toString()))
            .header(HEADER, x)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"));
    }

    @Test
    void issuerRejectsUnsupportedInputDenialAndRecursiveCredentials() throws Exception {
        mvc.perform(issue(Z)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("group_not_found"));
        mvc.perform(issue(X).header(HEADER, token(X, GroupRole.ADMIN))).andExpect(status().isUnauthorized());
        String url = "/api/groups/v1/domain-principal/attestations";
        for (String invalid : List.of("{}", "{\"kind\":\"other\",\"scope\":\"" + X + "\"}",
                "{\"kind\":\"membership\",\"scope\":\"bad\"}", "{\"kind\":\"membership\",\"scope\":\"" + X + "\",\"role\":\"ADMIN\"}")) {
            mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER))
                .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    private String acquire(UUID group) throws Exception {
        MvcResult response = mvc.perform(issue(group)).andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        String body = response.getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.attestation");
        Instant expires = Instant.parse(JsonPath.read(body, "$.expiresAt"));
        assertEquals(expires, SIGNER.verify(token).evidence().origin().expiresAt().orElseThrow());
        return token;
    }

    private static MockHttpServletRequestBuilder issue(UUID group) {
        return post("/api/groups/v1/domain-principal/attestations")
            .header(HttpHeaders.AUTHORIZATION, TestAccessTokens.bearer(USER)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"membership\",\"scope\":\"" + group + "\"}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MembershipFixture {
        @Bean @Primary @Scope("operation")
        GroupAuthorizationPort testMembershipAuthority(IdentityContext identity) {
            return group -> {
                UUID user = identity.requireUserId();
                if (!X.equals(group) && !Y.equals(group)) return Result.err(GroupAuthorizationVerificationFailure.INELIGIBLE);
                return Result.ok(new VerifiedGroupMembership(user, group, X.equals(group) ? GroupRole.ADMIN : GroupRole.MEMBER,
                    Instant.now()));
            };
        }
    }

    static final class Effects { private final AtomicInteger count = new AtomicInteger(); }
    record Failure(String requirementId, GroupAuthorizationFailure cause) { }

    static final class ConsumerEntry {
        private final GroupAuthorizationContext groups;
        private final Effects effects;
        ConsumerEntry(GroupAuthorizationContext groups, Effects effects) { this.groups = groups; this.effects = effects; }
        Result<Integer, Failure> transfer(UUID source, UUID destination) {
            return groups.requireAdministrator(source).mapError(failure -> new Failure("source-membership", failure))
                .flatMap(role -> groups.requireMember(destination).mapError(failure -> new Failure("destination-membership", failure)))
                .map(role -> effects.count.incrementAndGet());
        }
    }

    @TestComponent
    @RestController
    @ApiAccess
    static final class ConsumerController {
        private final OperationIngressDispatcher ingress;
        ConsumerController(OperationIngressDispatcher ingress) { this.ingress = ingress; }

        @PostMapping(ENDPOINT)
        ResponseEntity<?> transfer(@PathVariable UUID source, @RequestParam UUID destination) {
            return ingress.dispatch(ConsumerEntry.class, entry -> entry.transfer(source, destination))
                .<ResponseEntity<?>>fold(ResponseEntity::ok, failure -> switch (failure.cause()) {
                    case GroupAuthorizationFailure.AttestationRequired missing -> AttestationProblems.required(
                        new AttestationChallenge(failure.requirementId(), missing.requirement(),
                            failure.requirementId().equals("source-membership") ? AttestationAffinityPolicy.pathAffine()
                                : AttestationAffinityPolicy.queryAffine("destination")), "Group membership evidence is required.");
                    case GroupAuthorizationFailure.InsufficientPermissions denied -> ApiProblems.response(HttpStatus.FORBIDDEN,
                        "insufficient_permissions", "Access Denied", "Required role is not established.");
                    case GroupAuthorizationFailure.Unauthenticated ignored -> ApiProblems.response(HttpStatus.UNAUTHORIZED,
                        "authentication_required", "Authentication Required", "Authentication is required.");
                });
        }

        @GetMapping("/api/__test/distributed-authorization/role/{group}")
        Map<String, Object> role(@PathVariable UUID group) {
            return ingress.dispatch(GroupAuthorizationContext.class, context -> Map.of(
                "role", context.role(group).map(Enum::name).orElse("absent"),
                "member", context.isMember(group), "admin", context.isAdministrator(group)));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestAccessTokenConfiguration.class)
    static class Fixture {
        @Bean Effects effects() { return new Effects(); }
        @Bean @Scope("operation") ConsumerEntry consumerEntry(GroupAuthorizationContext groups, Effects effects) {
            return new ConsumerEntry(groups, effects);
        }
        @Bean ConsumerController consumerController(OperationIngressDispatcher ingress) { return new ConsumerController(ingress); }
    }
}

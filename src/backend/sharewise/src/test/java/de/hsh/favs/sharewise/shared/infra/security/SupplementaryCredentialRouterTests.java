package de.hsh.favs.sharewise.shared.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplementaryCredentialRouterTests {
    private static final String ISSUER = "https://authority.sharewise.test";

    @Test
    void registryRejectsAmbiguousRoutingOrProfileIdentityAndNonSubordinateRegistration() {
        var first = verifier(profile("membership", ISSUER, "membership+jwt", 2048));
        var sameRoute = verifier(profile("other", ISSUER, "membership+jwt", 2048));
        var sameProfileId = verifier(profile("membership", ISSUER, "other+jwt", 2048));
        var oversized = verifier(profile("membership", ISSUER, "membership+jwt", 2049));
        assertThrows(IllegalStateException.class, () -> new SupplementaryCredentialRouter(List.of(first, sameRoute)));
        assertThrows(IllegalStateException.class, () -> new SupplementaryCredentialRouter(List.of(first, sameProfileId)));
        assertThrows(IllegalStateException.class, () -> new SupplementaryCredentialRouter(List.of(oversized)));
        assertThrows(IllegalArgumentException.class, () -> new SupplementaryCredentialVerifier(TestAccessTokens.primaryCodec()));
    }

    @Test
    void distinctIssuerQualifiedProfilesRouteWithoutDiscoveringTrust() {
        var a = profile("membership", ISSUER, "membership+jwt", 2048);
        var b = profile("membership", "https://another.sharewise.test", "membership+jwt", 2048);
        var unknown = profile("membership", "https://untrusted.sharewise.test", "membership+jwt", 2048);
        var router = new SupplementaryCredentialRouter(List.of(verifier(a), verifier(b)));
        assertEquals(a, router.verify(token(a)).profile());
        assertEquals(b, router.verify(token(b)).profile());
        assertThrows(JwtCredentialException.class, () -> router.verify(token(unknown)));
        assertThrows(JwtCredentialException.class, () -> new SupplementaryCredentialRouter(List.of()).verify(token(a)));
    }

    private static SupplementaryCredentialVerifier verifier(JwtCredentialProfile profile) {
        return new SupplementaryCredentialVerifier(TestAccessTokens.codec(profile, TestAccessTokens.CLOCK));
    }

    private static JwtCredentialProfile profile(String name, String issuer, String type, int maxLength) {
        return new JwtCredentialProfile(name, issuer, type, Set.of("sw-api-v1"), JwtCredentialProfile.Kind.SUBORDINATE,
            Set.of(TestAccessTokens.PROFILE.id()), Duration.ofSeconds(120), Duration.ofSeconds(15), maxLength);
    }

    private static String token(JwtCredentialProfile profile) {
        var codec = TestAccessTokens.codec(profile, TestAccessTokens.CLOCK);
        UUID user = UUID.randomUUID();
        return codec.sign(new JwtIssueRequest(user, TestAccessTokens.sessionFor(user).toString(), UUID.randomUUID().toString(),
            TestAccessTokens.NOW, TestAccessTokens.NOW, TestAccessTokens.NOW.plusSeconds(120), Optional.of(TestAccessTokens.PROFILE.issuer()), Map.of()));
    }
}

package de.hsh.favs.sharewise.identity.internal.auth.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
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

class PrimaryAccessTokenIssuerTests {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final UUID USER = UUID.fromString("40000000-4000-7000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("40000000-4000-7000-8000-000000000002");

    @Test
    void issuedJwtRoundTripsThroughProductionVerifierAndCarriesCorrectPrimaryClaims() throws Exception {
        KeyPair pair = pair();
        JwtCredentialCodec codec = codec(pair, "https://iam.sharewise.example", "sharewise-api");
        PrimaryAccessTokenIssuer issuer = new PrimaryAccessTokenIssuer(codec);
        IssuedAccessToken issued = issuer.issue(USER, SESSION, NOW, NOW.plusSeconds(300));
        VerifiedJwtCredential verified = codec.verifyPrimary(issued.value());
        assertEquals(USER, verified.userId());
        assertEquals(SESSION.toString(), verified.sessionId());
        assertEquals("https://iam.sharewise.example", verified.credential().origin().issuer());
        assertEquals(Set.of("sharewise-api"), verified.credential().origin().audiences());
        assertEquals(NOW.plusSeconds(300), issued.expiresAt());
        assertThrows(JwtCredentialException.class,
                () -> codec(pair, "https://wrong.example", "sharewise-api").verifyPrimary(issued.value()));
        assertThrows(JwtCredentialException.class,
                () -> codec(pair, "https://iam.sharewise.example", "wrong-audience").verifyPrimary(issued.value()));
        assertThrows(JwtCredentialException.class, () -> codec(pair(),
                "https://iam.sharewise.example", "sharewise-api").verifyPrimary(issued.value()));
    }

    @Test
    void productionSignerFailuresDoNotReturnUnsignedOrFakeTokens() throws Exception {
        KeyPair pair = pair();
        JwtCredentialProfile profile = profile("https://iam.sharewise.example", "sharewise-api");
        JwtCredentialCodec verifyOnly = new JwtCredentialCodec(profile,
                new Es256JwtSignatureService(List.of(JwtEcKey.verification(
                        "current", (java.security.interfaces.ECPublicKey) pair.getPublic())), Optional.empty()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PrimaryAccessTokenIssuer issuer = new PrimaryAccessTokenIssuer(verifyOnly);
        assertThrows(IllegalStateException.class,
                () -> issuer.issue(USER, SESSION, NOW, NOW.plusSeconds(300)));
        assertThrows(IllegalArgumentException.class,
                () -> issuer.issue(USER, SESSION, NOW, NOW.plusSeconds(301)));
    }

    private static JwtCredentialCodec codec(KeyPair pair, String issuer, String audience) {
        return new JwtCredentialCodec(profile(issuer, audience),
                new Es256JwtSignatureService(List.of(JwtEcKey.signing("current", pair)),
                        Optional.of("current")), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JwtCredentialProfile profile(String issuer, String audience) {
        return new JwtCredentialProfile("iam-access", issuer, "sw-iam-access+jwt",
                Set.of(audience), JwtCredentialProfile.Kind.PRIMARY, Set.of(),
                Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
    }

    private static KeyPair pair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}

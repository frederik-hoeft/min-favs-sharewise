package de.hsh.favs.sharewise.testsupport.identity;

import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import java.nio.charset.StandardCharsets;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Test-signed primary IAM access tokens for exercising the real bearer ingress.
 *
 * <p>Tokens are signed for a fixed trusted profile with a per-JVM test key and verified on a
 * fixed clock, so they stay valid independently of wall-clock time. A context trusts them only
 * when it explicitly imports {@link TestAccessTokenConfiguration}, which publishes the matching
 * codec as IAM's primary codec. The fixture issues credentials only; it neither logs users in
 * nor creates IAM sessions, and it never stands in for {@code IdentityContext}.
 */
public final class TestAccessTokens {

    /** The instant at which every fixture codec verifies tokens. */
    public static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** The trusted primary profile; it mirrors the shape that IAM configures in production. */
    public static final JwtCredentialProfile PROFILE = new JwtCredentialProfile(
        "iam-access", "https://iam.sharewise.test", "sw-iam-access+jwt", Set.of("sharewise-api"),
        JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
    public static final String KEY_ID = "test";

    private static final KeyPair KEY = generateKey();
    private static final JwtCredentialCodec PRIMARY = codec(PROFILE, CLOCK);

    private TestAccessTokens() {
    }

    /** The codec that {@link TestAccessTokenConfiguration} publishes as IAM's primary codec. */
    public static JwtCredentialCodec primaryCodec() {
        return PRIMARY;
    }

    /** A codec for another profile or clock, signing with the fixture key under {@link #KEY_ID}. */
    public static JwtCredentialCodec codec(JwtCredentialProfile profile, Clock clock) {
        return codec(profile, KEY_ID, clock);
    }

    /** A codec signing with the fixture key under the given key ID. */
    public static JwtCredentialCodec codec(JwtCredentialProfile profile, String keyId, Clock clock) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(clock, "clock");
        return new JwtCredentialCodec(profile,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing(keyId, KEY)), Optional.of(keyId)), clock);
    }

    /** The canonical IAM session UUID that tokens issued without an explicit session carry for the user. */
    public static UUID sessionFor(UUID user) {
        Objects.requireNonNull(user, "user");
        return UUID.nameUUIDFromBytes(("session-" + user).getBytes(StandardCharsets.UTF_8));
    }

    /** A valid access token for the user, bound to the session from {@link #sessionFor(UUID)}. */
    public static String accessToken(UUID user) {
        return accessToken(user, sessionFor(user).toString());
    }

    /**
     * A valid access token for the user and raw {@code sid} claim. IAM admits only canonical
     * session UUIDs, so any other value yields a signed token that IAM ingress rejects.
     */
    public static String accessToken(UUID user, String sessionId) {
        return PRIMARY.sign(new JwtIssueRequest(user, sessionId, "test-token", NOW.minusSeconds(1),
            NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
    }

    /** The {@code Authorization} header value carrying a valid access token for the user. */
    public static String bearer(UUID user) {
        return "Bearer " + accessToken(user);
    }

    /** A correctly signed token whose validity ended beyond the profile's clock skew. */
    public static String expiredAccessToken(UUID user) {
        return PRIMARY.sign(new JwtIssueRequest(user, sessionFor(user).toString(), "expired-token",
            NOW.minusSeconds(240), NOW.minusSeconds(240), NOW.minusSeconds(35), Optional.empty(), Map.of()));
    }

    /** A token signed with the trusted key but for another profile, which ingress must reject. */
    public static String wrongProfileAccessToken(UUID user) {
        JwtCredentialProfile refresh = new JwtCredentialProfile("refresh", PROFILE.issuer(), "sw-refresh+jwt",
            PROFILE.audiences(), JwtCredentialProfile.Kind.PRIMARY, Set.of(), PROFILE.maximumLifetime(),
            PROFILE.clockSkew(), 4096);
        return codec(refresh, CLOCK).sign(new JwtIssueRequest(user, sessionFor(user).toString(),
            "wrong-profile-token", NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of()));
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
}

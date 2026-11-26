package de.hsh.favs.sharewise.groups.internal.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialVerifier;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Exercises explicit verification-only configuration and local key overlap/removal. */
class GroupAttestationConfigurationTests {
    private static final String GROUP_ISSUER = "https://groups.sharewise.example";
    private static final String PRIMARY_ISSUER = "https://iam.sharewise.example";
    private static final String PREFIX = "sharewise.groups.attestation.";
    @TempDir Path directory;

    @Test
    void disabledConfigurationDoesNotEstablishGroupTrustAndEnabledWithoutKeysFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(GroupAttestationConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("groupMembershipVerifier"));
            assertFalse(context.containsBean("groupCredentialValidator"));
        });
        configured().run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void configuredKeysAreVerificationOnlyWithBoundedDefaultProfileAndMatchingValidator() throws Exception {
        KeyPair pair = keyPair();
        Path publicKey = writePublic("first.pem", pair);
        configured().withPropertyValues(PREFIX + "keys[0].id=first", PREFIX + "keys[0].public-key-file=" + publicKey)
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.containsBean("groupCredentialValidator"));
                var verifier = context.getBean(SupplementaryCredentialVerifier.class);
                var profile = verifier.profile();
                assertEquals(Set.of("sw-api-v1"), profile.audiences());
                assertEquals(Set.of(new JwtCredentialProfile.Id(PRIMARY_ISSUER, "iam-access")), profile.trustedPrimaryProfiles());
                assertEquals(Duration.ofSeconds(120), profile.maximumLifetime());
                assertEquals(Duration.ofSeconds(15), profile.clockSkew());
                var request = request();
                assertEquals(request.subject(), verifier.verify(signer(profile, "first", pair).sign(request)).userId());
                assertThrows(IllegalStateException.class, () -> context.getBean(GroupMembershipTrust.class).codec().sign(request));
            });
    }

    @Test
    void keyOverlapAndRemovalAndExplicitAudienceDoNotIntroduceFallback() throws Exception {
        KeyPair oldPair = keyPair();
        KeyPair newPair = keyPair();
        Path oldPublic = writePublic("old.pem", oldPair);
        Path newPublic = writePublic("new.pem", newPair);
        var profile = GroupMembershipTrust.profile(GROUP_ISSUER, "sw-api-v1-finance", PRIMARY_ISSUER, Duration.ofSeconds(15));
        var request = request();
        String oldToken = signer(profile, "old", oldPair).sign(request);
        String newToken = signer(profile, "new", newPair).sign(request);
        String wrongAudience = signer(GroupMembershipTrust.profile(GROUP_ISSUER, "sw-api-v1", PRIMARY_ISSUER,
                Duration.ofSeconds(15)), "new", newPair).sign(request);
        configured().withPropertyValues(PREFIX + "audience=sw-api-v1-finance", PREFIX + "keys[0].id=old",
            PREFIX + "keys[0].public-key-file=" + oldPublic, PREFIX + "keys[1].id=new", PREFIX + "keys[1].public-key-file=" + newPublic)
            .run(context -> {
                assertNull(context.getStartupFailure());
                var verifier = context.getBean(SupplementaryCredentialVerifier.class);
                assertEquals(request.subject(), verifier.verify(oldToken).userId());
                assertEquals(request.subject(), verifier.verify(newToken).userId());
                assertThrows(JwtCredentialException.class, () -> verifier.verify(wrongAudience));
            });
        configured().withPropertyValues(PREFIX + "audience=sw-api-v1-finance", PREFIX + "keys[0].id=new",
            PREFIX + "keys[0].public-key-file=" + newPublic).run(context -> {
                assertNull(context.getStartupFailure());
                var verifier = context.getBean(SupplementaryCredentialVerifier.class);
                assertEquals(request.subject(), verifier.verify(newToken).userId());
                assertThrows(JwtCredentialException.class, () -> verifier.verify(oldToken));
            });
    }

    private static ApplicationContextRunner configured() {
        return new ApplicationContextRunner().withUserConfiguration(GroupAttestationConfiguration.class)
            .withPropertyValues(PREFIX + "enabled=true", PREFIX + "issuer=" + GROUP_ISSUER,
                PREFIX + "trusted-primary-issuer=" + PRIMARY_ISSUER);
    }

    private static JwtIssueRequest request() {
        Instant now = Instant.now();
        return new JwtIssueRequest(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            now, now, now.plusSeconds(120), Optional.of(PRIMARY_ISSUER), Map.of("group_id", UUID.randomUUID().toString(), "role", "MEMBER"));
    }

    private static JwtCredentialCodec signer(JwtCredentialProfile profile, String keyId, KeyPair pair) {
        return new JwtCredentialCodec(profile, new Es256JwtSignatureService(List.of(JwtEcKey.signing(keyId, pair)),
            Optional.of(keyId)), Clock.systemUTC());
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private Path writePublic(String filename, KeyPair pair) throws Exception {
        return Files.writeString(directory.resolve(filename), "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n");
    }
}

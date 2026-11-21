package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.token.PrimaryAccessTokenIssuer;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Fail-closed production configuration: no generated test token or untrusted default keys. */
class IamAuthenticationConfigurationTests {
    @TempDir Path directory;

    @Test
    void omittedEnableFlagExposesNeitherProductionIssuerNorEncoder() {
        new ApplicationContextRunner().withUserConfiguration(IamAuthenticationConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("iamPrimaryJwtCredentialCodec"));
                    assertFalse(context.containsBean("iamCredentialEncoder"));
                    assertFalse(context.containsBean("iamRegistrationService"));
                });
    }

    @Test
    void enabledWithoutTrustedKeysAndBlocklistCannotStart() {
        new ApplicationContextRunner().withUserConfiguration(IamAuthenticationConfiguration.class)
                .withPropertyValues("sharewise.iam.auth.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void configuredPrimaryProfileUsesSameCodecForIssuanceAndVerification() throws Exception {
        KeyPair pair = newEcPair();
        Path publicKey = directory.resolve("public.pem");
        Path privateKey = directory.resolve("private.pem");
        writePem(publicKey, "PUBLIC KEY", pair.getPublic().getEncoded());
        writePem(privateKey, "PRIVATE KEY", pair.getPrivate().getEncoded());
        Path list = directory.resolve("list.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            content.append("bad-password-").append(i).append('\n');
        }
        Files.writeString(list, content.toString(), StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(list)));
        new ApplicationContextRunner().withUserConfiguration(IamAuthenticationConfiguration.class)
                .withPropertyValues(
                        "sharewise.iam.auth.enabled=true",
                        "sharewise.iam.auth.issuer=https://iam.sharewise.example",
                        "sharewise.iam.auth.audience=sharewise-api",
                        "sharewise.iam.auth.active-key-id=primary-2026",
                        "sharewise.iam.auth.keys[0].id=primary-2026",
                        "sharewise.iam.auth.keys[0].public-key-file=" + publicKey,
                        "sharewise.iam.auth.keys[0].private-key-file=" + privateKey,
                        "sharewise.iam.auth.blocklist-file=" + list,
                        "sharewise.iam.auth.blocklist-version=synthetic-test-2026",
                        "sharewise.iam.auth.blocklist-sha256=" + sha,
                        "sharewise.iam.auth.max-concurrent-hashes=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("iamRegistrationService"));
                    assertTrue(context.containsBean("iamLoginService"));
                    JwtCredentialCodec codec = context.getBean("iamPrimaryJwtCredentialCodec", JwtCredentialCodec.class);
                    PrimaryAccessTokenIssuer issuer = new PrimaryAccessTokenIssuer(codec);
                    UUID user = UUID.fromString("40000000-4000-7000-8000-000000000001");
                    UUID session = UUID.fromString("40000000-4000-7000-8000-000000000002");
                    Instant now = Instant.now();
                    String signed = issuer.issue(user, session, now, now.plusSeconds(300)).value();
                    assertEquals(user, codec.verifyPrimary(signed).userId());
                    assertEquals(session.toString(), codec.verifyPrimary(signed).sessionId());
                    assertEquals("https://iam.sharewise.example", codec.profile().issuer());
                    assertTrue(context.getBean(IamAuthenticationConfiguration.DummyVerifier.class)
                            .encoded().startsWith("$argon2id$v=19$m=65536,t=3,p=4$"));
                });
    }

    private static KeyPair newEcPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static void writePem(Path path, String envelope, byte[] bytes) throws Exception {
        Files.writeString(path, "-----BEGIN " + envelope + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END " + envelope + "-----\n");
    }
}

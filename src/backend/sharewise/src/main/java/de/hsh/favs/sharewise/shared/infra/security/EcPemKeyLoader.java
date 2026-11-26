package de.hsh.favs.sharewise.shared.infra.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads administrator-provisioned local SPKI public and PKCS#8 private PEM files. */
public final class EcPemKeyLoader {
    private EcPemKeyLoader() { }

    public static JwtEcKey verification(String id, String publicKeyFile) {
        return JwtEcKey.verification(id, publicKey(publicKeyFile));
    }

    public static JwtEcKey signing(String id, String publicKeyFile, String privateKeyFile) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(pem(privateKeyFile, "PRIVATE KEY")));
            if (!(privateKey instanceof ECPrivateKey ecPrivate)) {
                throw new IllegalArgumentException("Expected an EC signing key");
            }
            return JwtEcKey.signing(id, new KeyPair(publicKey(publicKeyFile), ecPrivate));
        } catch (Exception failure) {
            // Do not put private key material or content in error messages.
            throw new IllegalStateException("Invalid locally configured JWT signing key");
        }
    }

    private static ECPublicKey publicKey(String source) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(pem(source, "PUBLIC KEY")));
            if (!(publicKey instanceof ECPublicKey ecPublic)) {
                throw new IllegalArgumentException("Expected an EC verification key");
            }
            return ecPublic;
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid locally configured JWT verification key");
        }
    }

    private static byte[] pem(String source, String kind) throws IOException {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("JWT key file is missing");
        }
        Path file = Path.of(source);
        if (Files.size(file) > 16_384L) {
            throw new IllegalArgumentException("JWT key file exceeds size limit");
        }
        String content = Files.readString(file, StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN " + kind + "-----";
        String end = "-----END " + kind + "-----";
        if (!content.startsWith(begin) || !content.endsWith(end)) {
            throw new IllegalArgumentException("Unexpected JWT PEM envelope");
        }
        String body = content.substring(begin.length(), content.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}

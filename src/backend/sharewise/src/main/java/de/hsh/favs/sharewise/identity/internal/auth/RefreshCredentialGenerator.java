package de.hsh.favs.sharewise.identity.internal.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Issues high-entropy initial refresh credentials; only the SHA-256 verifier is persisted. */
public final class RefreshCredentialGenerator {

    public static final int SECRET_BYTES = 32;
    public static final int VERIFIER_BYTES = 32;

    private final SecureRandom random;

    public RefreshCredentialGenerator() {
        this(new SecureRandom());
    }

    RefreshCredentialGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public GeneratedCredential generate(UUID tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        byte[] secret = new byte[SECRET_BYTES];
        try {
            random.nextBytes(secret);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            byte[] verifier = MessageDigest.getInstance("SHA-256").digest(secret);
            return new GeneratedCredential(tokenId, tokenId + "." + encoded, verifier);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 provider is unavailable", failure);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** The bearer value is released only after the corresponding transaction commits. */
    public static final class GeneratedCredential {
        private final UUID tokenId;
        private final String cookieValue;
        private final byte[] verifierHash;

        private GeneratedCredential(UUID tokenId, String cookieValue, byte[] verifierHash) {
            this.tokenId = tokenId;
            this.cookieValue = cookieValue;
            this.verifierHash = verifierHash.clone();
        }

        public UUID tokenId() {
            return tokenId;
        }

        public String cookieValue() {
            return cookieValue;
        }

        public byte[] verifierHash() {
            return verifierHash.clone();
        }

        @Override
        public String toString() {
            return "GeneratedCredential[redacted]";
        }
    }
}

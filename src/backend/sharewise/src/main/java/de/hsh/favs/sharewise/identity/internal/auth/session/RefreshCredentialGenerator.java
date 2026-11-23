package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Issues high-entropy initial refresh credentials; only the SHA-256 verifier is persisted. */
public final class RefreshCredentialGenerator {

    /** Number of random secret bytes in each refresh bearer. */
    public static final int SECRET_BYTES = 32;

    /** Number of bytes in the stored SHA-256 verifier. */
    public static final int VERIFIER_BYTES = 32;

    private final SecureRandom random;

    /** Creates a generator backed by the platform's cryptographic random source. */
    public RefreshCredentialGenerator() {
        this(new SecureRandom());
    }

    RefreshCredentialGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Generates an opaque refresh bearer and its separately persistable verifier.
     * Generating the credential does not persist it; the caller must store the verifier
     * and commit before exposing the cookie value to a client.
     *
     * @param tokenId database-allocated selector for the new refresh generation
     * @return generated bearer and SHA-256 verifier associated with the selector
     */
    public GeneratedCredential generate(UUID tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        byte[] secret = new byte[SECRET_BYTES];
        try {
            random.nextBytes(secret);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            return new GeneratedCredential(tokenId, tokenId + "." + encoded, verifierFor(secret));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** Shared SHA-256 verifier computation for initial issuance and subsequent verification. */
    static byte[] verifierFor(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 provider is unavailable", failure);
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

        /** @return the non-secret selector associated with this generation */
        public UUID tokenId() {
            return tokenId;
        }

        /** @return the secret-bearing cookie value; release only after commit */
        public String cookieValue() {
            return cookieValue;
        }

        /** @return a defensive copy of the SHA-256 verifier for persistence */
        public byte[] verifierHash() {
            return verifierHash.clone();
        }

        @Override
        public String toString() {
            return "GeneratedCredential[redacted]";
        }
    }
}

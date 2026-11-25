package de.hsh.favs.sharewise.shared.infra.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Issues high-entropy opaque application tokens; only the SHA-256 verifier is persisted. */
public final class ApplicationTokenGenerator {

    private final SecureRandom random;

    /** Creates a generator backed by the platform's cryptographic random source. */
    public ApplicationTokenGenerator() {
        this(new SecureRandom());
    }

    ApplicationTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Generates an opaque application token and its separately persistable verifier.
     * Generating the token does not persist it; the caller must store the verifier
     * and commit before exposing the bearer value to a client.
     *
     * @param selector caller-allocated selector for the new token
     * @return generated bearer and SHA-256 verifier associated with the selector
     */
    public GeneratedToken generate(UUID selector) {
        Objects.requireNonNull(selector, "selector");
        byte[] secret = new byte[ApplicationToken.SECRET_BYTES];
        try {
            random.nextBytes(secret);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            return new GeneratedToken(selector, selector + "." + encoded, ApplicationToken.verifierFor(secret));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** The bearer value is released only after the corresponding transaction commits. */
    public static final class GeneratedToken {
        private final UUID selector;
        private final String value;
        private final byte[] verifierHash;

        private GeneratedToken(UUID selector, String value, byte[] verifierHash) {
            this.selector = selector;
            this.value = value;
            this.verifierHash = verifierHash.clone();
        }

        /** @return the non-secret selector associated with this token */
        public UUID selector() {
            return selector;
        }

        /** @return the secret-bearing token value; release only after commit */
        public String value() {
            return value;
        }

        /** @return a defensive copy of the SHA-256 verifier for persistence */
        public byte[] verifierHash() {
            return verifierHash.clone();
        }

        @Override
        public String toString() {
            return "GeneratedToken[redacted]";
        }
    }
}

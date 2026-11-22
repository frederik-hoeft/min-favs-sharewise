package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Parsed opaque refresh bearer. The clear secret is discarded after hashing. */
public final class PresentedRefreshCredential {

    private static final int SELECTOR_LENGTH = 36;
    private static final int ENCODED_SECRET_LENGTH = 43;

    private final UUID tokenId;
    private final byte[] verifierHash;

    private PresentedRefreshCredential(UUID tokenId, byte[] verifierHash) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.verifierHash = verifierHash.clone();
    }

    /**
     * Parses IAM's canonical {@code UUID.base64url(secret)} bearer format and hashes the secret.
     * Parsing failures are intentionally indistinguishable from invalid credentials; detailed
     * field-validation errors are inappropriate for an opaque authentication secret.
     *
     * @param cookieValue untrusted cookie value; may be {@code null}
     * @return a selector and verifier hash, or empty for any malformed encoding
     */
    public static Optional<PresentedRefreshCredential> parse(String cookieValue) {
        if (cookieValue == null || cookieValue.length() != SELECTOR_LENGTH + 1 + ENCODED_SECRET_LENGTH
                || cookieValue.charAt(SELECTOR_LENGTH) != '.') {
            return Optional.empty();
        }
        String selector = cookieValue.substring(0, SELECTOR_LENGTH);
        String encoded = cookieValue.substring(SELECTOR_LENGTH + 1);
        UUID tokenId;
        byte[] secret;
        try {
            tokenId = UUID.fromString(selector);
            secret = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        try {
            if (!selector.equals(tokenId.toString()) || secret.length != RefreshCredentialGenerator.SECRET_BYTES
                    || !encoded.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))) {
                return Optional.empty();
            }
            return Optional.of(new PresentedRefreshCredential(
                    tokenId, RefreshCredentialGenerator.verifierFor(secret)));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * @return the non-secret database selector extracted from the presented credential
     */
    public UUID tokenId() {
        return tokenId;
    }

    /**
     * Compares the selector and fixed-length SHA-256 verifier without comparing raw secrets.
     * The verifier comparison uses constant-time byte comparison.
     *
     * @param stored persisted token generation to authenticate against
     * @return {@code true} only if both selector and secret verifier match
     */
    public boolean matches(RefreshTokenRecord stored) {
        Objects.requireNonNull(stored, "stored");
        return tokenId.equals(stored.id())
                && MessageDigest.isEqual(verifierHash, stored.verifierHash());
    }

    @Override
    public String toString() {
        return "PresentedRefreshCredential[redacted]";
    }
}

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

    /** Require the canonical UUID and unpadded 32-byte Base64URL encoding issued by IAM. */
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

    public UUID tokenId() {
        return tokenId;
    }

    /** Constant-time comparison for fixed-length SHA-256 verifiers. */
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

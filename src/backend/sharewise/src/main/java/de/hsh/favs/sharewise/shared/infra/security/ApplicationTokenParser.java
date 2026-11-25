package de.hsh.favs.sharewise.shared.infra.security;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Parses untrusted opaque application tokens, retaining only the selector and verifier. */
public final class ApplicationTokenParser {

    private static final int SELECTOR_LENGTH = 36;
    private static final int ENCODED_SECRET_LENGTH = 43;

    /**
     * Parses the canonical {@code UUID.base64url(secret)} bearer format and hashes the secret.
     * Parsing failures are intentionally indistinguishable from invalid credentials; detailed
     * field-validation errors are inappropriate for an opaque authentication secret.
     *
     * @param value untrusted token value; may be {@code null}
     * @return a selector and verifier hash, or empty for any malformed encoding
     */
    public Optional<ApplicationToken> parse(String value) {
        if (value == null || value.length() != SELECTOR_LENGTH + 1 + ENCODED_SECRET_LENGTH
                || value.charAt(SELECTOR_LENGTH) != '.') {
            return Optional.empty();
        }
        String selector = value.substring(0, SELECTOR_LENGTH);
        String encoded = value.substring(SELECTOR_LENGTH + 1);
        UUID tokenSelector;
        byte[] secret;
        try {
            tokenSelector = UUID.fromString(selector);
            secret = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        try {
            if (!selector.equals(tokenSelector.toString()) || secret.length != ApplicationToken.SECRET_BYTES
                    || !encoded.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))) {
                return Optional.empty();
            }
            return Optional.of(new ApplicationToken(
                    tokenSelector, ApplicationToken.verifierFor(secret)));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}

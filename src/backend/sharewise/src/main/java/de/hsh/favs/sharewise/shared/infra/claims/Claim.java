package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.Objects;

/**
 * An immutable assertion retaining the verified origin of its credential.
 *
 * <p>A claim alone does not express a permission decision.
 *
 * @param type claim type or name
 * @param value asserted value
 * @param origin verified source metadata for this claim's credential
 */
public record Claim(String type, String value, ClaimOrigin origin) {

    /** Validates the required claim values and its origin. */
    public Claim {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(origin, "origin");
        if (type.isBlank()) {
            throw new IllegalArgumentException("Claim type must not be blank");
        }
    }
}

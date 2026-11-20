package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.Objects;

/** An immutable assertion from one verified credential; not an authorization decision. */
public record Claim(String type, String value) {

    public Claim {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        if (type.isBlank()) {
            throw new IllegalArgumentException("Claim type must not be blank");
        }
    }
}

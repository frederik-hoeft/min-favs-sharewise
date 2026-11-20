package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;
import java.util.Objects;

/** One verified credential's ordered claims, kept together with their source. */
public record ClaimSet(ClaimSource source, List<Claim> claims) {

    public ClaimSet {
        Objects.requireNonNull(source, "source");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }
}

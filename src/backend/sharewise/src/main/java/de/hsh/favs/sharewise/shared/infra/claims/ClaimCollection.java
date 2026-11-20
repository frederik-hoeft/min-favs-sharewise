package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;
import java.util.Objects;

/**
 * One verified credential's ordered claims, with consistent per-claim provenance.
 *
 * @param origin verified metadata shared by every claim in the collection
 * @param claims ordered, immutable list of claims; repeated claim types are supported
 */
public record ClaimCollection(ClaimOrigin origin, List<Claim> claims) {

    /** Copies the claims and rejects those whose origin differs from this collection. */
    public ClaimCollection {
        Objects.requireNonNull(origin, "origin");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        for (Claim claim : claims) {
            if (!origin.equals(claim.origin())) {
                throw new IllegalArgumentException("Claim origin must match collection origin");
            }
        }
    }
}

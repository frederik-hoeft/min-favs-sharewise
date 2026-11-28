package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;
import java.util.Objects;

/**
 * Application-level representation of one verified credential and its ordered claims.
 *
 * <p>Verification and any required binding to the current primary identity must
 * precede publication in an {@link AuthorizationContext}. Constructing
 * this record only validates structure; it does not establish trust or grant
 * permission to perform a business operation.
 *
 * @param origin metadata shared by every claim in this credential
 * @param claims ordered, immutable list of claims; repeated claim types are supported
 */
public record AuthorizationCredential(ClaimOrigin origin, List<Claim> claims) {

    /** Copies claims and rejects those whose origin differs from this credential. */
    public AuthorizationCredential {
        Objects.requireNonNull(origin, "origin");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        for (Claim claim : claims) {
            if (!origin.equals(claim.origin())) {
                throw new IllegalArgumentException("Claim origin must match credential origin");
            }
        }
    }
}

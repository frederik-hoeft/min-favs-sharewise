package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.Objects;

/** An individual query hit, retaining the whole originating credential and its source. */
public record ClaimMatch(ClaimSet claimSet, Claim claim) {

    public ClaimMatch {
        Objects.requireNonNull(claimSet, "claimSet");
        Objects.requireNonNull(claim, "claim");
    }
}

package de.hsh.favs.sharewise.shared.infra.claims;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.List;

/** Distinct typed input for source-verified supplementary candidates, in ingress order. */
public record SupplementaryCredentialInputs(List<VerifiedJwtCredentialCandidate> candidates) {
    public SupplementaryCredentialInputs {
        candidates = List.copyOf(candidates);
        if (candidates.stream().anyMatch(candidate -> candidate.kind() != JwtCredentialProfile.Kind.SUBORDINATE)) {
            throw new IllegalArgumentException("Supplementary input contains non-subordinate evidence");
        }
    }
}

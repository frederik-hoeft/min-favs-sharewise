package de.hsh.favs.sharewise.shared.infra.web;

import de.hsh.favs.sharewise.shared.infra.claims.CredentialRequirement;
import java.util.Objects;

/** Consumer-owned requirement identity and HTTP affinity attached to a missing-evidence challenge. */
public record AttestationChallenge(String requirementId, CredentialRequirement credential, AttestationAffinityPolicy cache) {
    public AttestationChallenge {
        if (requirementId == null || requirementId.isBlank() || requirementId.length() > 128
                || requirementId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid requirement ID");
        }
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(cache, "cache");
    }
}

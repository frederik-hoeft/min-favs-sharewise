package de.hsh.favs.sharewise.shared.infra.security;

import java.util.Optional;

/** Contextual rejection before publication; only infrastructure may correlate the rejected proof to ingress. */
public final class SupplementaryCredentialRejected extends RuntimeException {
    private final transient VerifiedJwtCredentialCandidate candidate;

    /** Ambiguous rejection (e.g. conflicting individually valid assertions). */
    public SupplementaryCredentialRejected() {
        this(null);
    }

    /** Identifies an exact proof, not a token, claim value or provider-relative index. */
    public SupplementaryCredentialRejected(VerifiedJwtCredentialCandidate candidate) {
        super("Supplementary credential admission failed", new JwtCredentialException());
        this.candidate = candidate;
    }

    public Optional<VerifiedJwtCredentialCandidate> candidate() { return Optional.ofNullable(candidate); }
}

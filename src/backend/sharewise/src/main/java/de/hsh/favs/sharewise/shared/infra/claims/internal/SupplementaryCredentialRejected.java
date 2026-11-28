package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.Optional;

/** Internal admission failure. Only the pre-entry hook certifies HTTP recovery. */
public final class SupplementaryCredentialRejected extends RuntimeException {
    private final transient VerifiedJwtCredentialCandidate candidate;
    private final boolean preEntry;

    SupplementaryCredentialRejected(VerifiedJwtCredentialCandidate candidate) {
        this(candidate, false);
    }

    private SupplementaryCredentialRejected(VerifiedJwtCredentialCandidate candidate, boolean preEntry) {
        super("Supplementary credential admission failed", new JwtCredentialException());
        this.candidate = candidate;
        this.preEntry = preEntry;
    }

    SupplementaryCredentialRejected certifyPreEntry() {
        return new SupplementaryCredentialRejected(candidate, true);
    }

    public boolean isPreEntry() { return preEntry; }
    public Optional<VerifiedJwtCredentialCandidate> candidate() { return Optional.ofNullable(candidate); }
}

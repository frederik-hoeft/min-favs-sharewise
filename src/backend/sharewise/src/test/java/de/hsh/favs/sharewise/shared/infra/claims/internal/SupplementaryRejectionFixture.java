package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;

/** Test-only access to the non-certified rejection for HTTP error-mapping regressions. */
public final class SupplementaryRejectionFixture {
    private SupplementaryRejectionFixture() { }

    public static RuntimeException rejection(VerifiedJwtCredentialCandidate candidate) { return new SupplementaryCredentialRejected(candidate); }
}

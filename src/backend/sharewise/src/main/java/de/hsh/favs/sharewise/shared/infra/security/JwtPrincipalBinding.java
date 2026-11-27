package de.hsh.favs.sharewise.shared.infra.security;

import java.util.Optional;

/** Shared mandatory primary/subordinate correlation rule. Does not reverify signatures. */
public final class JwtPrincipalBinding {
    private JwtPrincipalBinding() { }

    public static void requireBound(VerifiedJwtCredentialCandidate primary,
                                    VerifiedJwtCredentialCandidate subordinate) {
        if (primary == null || subordinate == null
            || primary.kind() != JwtCredentialProfile.Kind.PRIMARY
            || subordinate.kind() != JwtCredentialProfile.Kind.SUBORDINATE
            || !subordinate.profile().trustedPrimaryProfiles().contains(primary.profile().id())
            || !subordinate.principalIssuer().equals(Optional.of(primary.profile().issuer()))
            || !subordinate.userId().equals(primary.userId())
            || !subordinate.sessionId().equals(primary.sessionId())) {
            throw new JwtCredentialException();
        }
    }
}

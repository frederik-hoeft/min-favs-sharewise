package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import java.util.Objects;
import java.util.Optional;

/** Transport-independent, immutable result of the trusted primary-ingress stage. */
final class VerifiedIdentityIngress {

    private final Optional<VerifiedJwtCredential> primary;

    private VerifiedIdentityIngress(Optional<VerifiedJwtCredential> primary) {
        this.primary = primary;
    }

    static VerifiedIdentityIngress anonymous() {
        return new VerifiedIdentityIngress(Optional.empty());
    }

    static VerifiedIdentityIngress authenticated(VerifiedJwtCredential primary) {
        Objects.requireNonNull(primary, "primary");
        if (primary.kind() != JwtCredentialProfile.Kind.PRIMARY) {
            throw new IllegalArgumentException("Only verified primary credentials can authenticate ingress");
        }
        return new VerifiedIdentityIngress(Optional.of(primary));
    }

    Optional<VerifiedJwtCredential> primary() {
        return primary;
    }
}

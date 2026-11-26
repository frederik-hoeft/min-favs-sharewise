package de.hsh.favs.sharewise.shared.infra.security;

import java.util.Objects;

/** A locally registered subordinate verifier. Routing hints do not configure trust. */
public final class SupplementaryCredentialVerifier {
    private final JwtCredentialCodec codec;

    public SupplementaryCredentialVerifier(JwtCredentialCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        if (codec.profile().kind() != JwtCredentialProfile.Kind.SUBORDINATE) {
            throw new IllegalArgumentException("Supplementary verifier requires a subordinate profile");
        }
    }

    public JwtCredentialProfile profile() { return codec.profile(); }

    public VerifiedJwtCredentialCandidate verify(String token) { return codec.verify(token); }
}

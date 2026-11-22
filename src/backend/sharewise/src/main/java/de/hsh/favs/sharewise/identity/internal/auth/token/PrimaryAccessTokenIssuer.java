package de.hsh.favs.sharewise.identity.internal.auth.token;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** IAM primary-access issuance through the same trusted P2.1.0 codec used for ingress. */
public final class PrimaryAccessTokenIssuer implements AccessTokenIssuer {
    private final JwtCredentialCodec codec;

    public PrimaryAccessTokenIssuer(JwtCredentialCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        if (codec.profile().kind() != JwtCredentialProfile.Kind.PRIMARY) {
            throw new IllegalArgumentException("IAM access issuer requires a primary JWT profile");
        }
    }

    @Override
    public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant expiresAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        String signed = codec.sign(new JwtIssueRequest(userId, sessionId.toString(),
                UUID.randomUUID().toString(), issuedAt, issuedAt, expiresAt, Optional.empty(), Map.of()));
        return new IssuedAccessToken(signed, expiresAt);
    }
}

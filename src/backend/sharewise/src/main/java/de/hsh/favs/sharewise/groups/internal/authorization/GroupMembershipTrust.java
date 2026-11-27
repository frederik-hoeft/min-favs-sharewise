package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.shared.infra.claims.CredentialRequirement;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialRouter;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Single Group-owned source of the membership profile and logical acquisition descriptor. */
record GroupMembershipTrust(JwtCredentialCodec codec, boolean signingEnabled) {
    static final String AUTHORITY = "groups";
    static final String KIND = "membership";
    static final String PROFILE = "groups-membership";
    static final String TYPE = "sw-groups-membership+jwt";
    static final Duration LIFETIME = Duration.ofSeconds(120);

    GroupMembershipTrust { Objects.requireNonNull(codec); }

    static JwtCredentialProfile profile(String issuer, String audience, String primaryIssuer, Duration skew) {
        return new JwtCredentialProfile(PROFILE, issuer, TYPE, Set.of(audience),
            JwtCredentialProfile.Kind.SUBORDINATE, Set.of(new JwtCredentialProfile.Id(primaryIssuer, "iam-access")),
            LIFETIME, skew, SupplementaryCredentialRouter.MAX_TOKEN_LENGTH);
    }

    static CredentialRequirement requirement(UUID groupId) {
        return new CredentialRequirement(AUTHORITY, KIND, groupId.toString());
    }
}

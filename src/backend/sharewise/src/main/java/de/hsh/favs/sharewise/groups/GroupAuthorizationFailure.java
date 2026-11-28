package de.hsh.favs.sharewise.groups;

import de.hsh.favs.sharewise.shared.infra.claims.CredentialRequirement;
import java.util.Objects;
import java.util.UUID;

/** Expected authorization outcomes, independent of HTTP challenge mapping and affinity. */
public sealed interface GroupAuthorizationFailure {
    record Unauthenticated() implements GroupAuthorizationFailure { }

    record AttestationRequired(CredentialRequirement requirement) implements GroupAuthorizationFailure {
        public AttestationRequired { Objects.requireNonNull(requirement); }
    }

    record InsufficientPermissions(UUID groupId) implements GroupAuthorizationFailure {
        public InsufficientPermissions { Objects.requireNonNull(groupId); }
    }
}

package de.hsh.favs.sharewise.identity.internal.profile;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** IAM-private profile projections and writes within an active transaction segment. */
public interface AccountProfileStore {
    /** An absent or disabled account yields no profile; missing required data is an integrity failure. */
    Optional<AccountProfile> findActive(UUID userId);

    /** Serializes with changes to the account row before updating its required profile. */
    Optional<AccountProfile> setDisplayNameIfActive(UUID userId, DisplayName displayName);

    /** Resolves known IDs, including disabled accounts, in a single bounded projection. */
    List<PublicProfile> lookup(List<UUID> userIds);
}

package de.hsh.favs.sharewise.identity.internal.profile;

import java.util.Optional;
import java.util.UUID;

/** IAM-private profile projections and writes within an active transaction segment. */
public interface AccountProfileStore {
    /** An absent or disabled account yields no profile; an absent profile row yields a null name. */
    Optional<AccountProfile> findActive(UUID userId);

    /** Serializes with changes to the account row before inserting/updating its optional profile. */
    Optional<AccountProfile> setDisplayNameIfActive(UUID userId, String displayName);
}

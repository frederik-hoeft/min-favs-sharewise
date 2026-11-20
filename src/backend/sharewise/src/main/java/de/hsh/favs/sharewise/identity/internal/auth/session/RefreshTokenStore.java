package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.util.UUID;

/** Initial refresh-token storage; rotation and reuse handling belong to P2.1b. */
public interface RefreshTokenStore {

    /** Allocate a database UUIDv7 selector before creating the secret-bearing cookie. */
    UUID newTokenId();

    /** Store only the verifier, never the raw secret or cookie bearer value. */
    void insert(RefreshTokenRecord record);
}

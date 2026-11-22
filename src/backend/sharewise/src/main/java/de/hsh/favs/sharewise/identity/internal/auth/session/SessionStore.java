package de.hsh.favs.sharewise.identity.internal.auth.session;

import java.util.UUID;

/** Durable login sessions created in the caller's active transaction segment. */
public interface SessionStore {

    /** The database allocates the UUIDv7 session ID used as the stable primary-token sid. */
    LoginSession insert(UUID userId, SessionPolicy.Deadlines deadlines);
}

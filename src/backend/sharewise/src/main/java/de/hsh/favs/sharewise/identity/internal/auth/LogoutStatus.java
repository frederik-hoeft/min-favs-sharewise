package de.hsh.favs.sharewise.identity.internal.auth;

/** The HTTP adapter may clear cookies after either successful idempotent logout outcome. */
public enum LogoutStatus {
    REVOKED,
    NO_SESSION
}

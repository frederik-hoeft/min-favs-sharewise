package de.hsh.favs.sharewise.identity.internal.auth;

/** The HTTP adapter may clear cookies after either successful idempotent logout outcome. */
public enum LogoutStatus {
    /** A verified session was revoked, including a verified replay of a consumed generation. */
    REVOKED,

    /** No valid refresh credential identified a session; logout is still successful. */
    NO_SESSION
}

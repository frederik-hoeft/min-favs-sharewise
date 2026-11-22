package de.hsh.favs.sharewise.identity.internal.auth;

/** Transport-neutral failures: invalid/expired credentials stay intentionally indistinguishable. */
public enum SessionLifecycleFailure {
    REFRESH_UNAVAILABLE,
    SESSION_CONTEXT_CHANGED
}

package de.hsh.favs.sharewise.identity.internal.auth;

/** Transport-neutral failures: invalid/expired credentials stay intentionally indistinguishable. */
public enum SessionLifecycleFailure {
    /** Refresh is unavailable; no distinction is exposed for malformed, invalid, or expired credentials. */
    REFRESH_UNAVAILABLE,

    /** A verified active credential belongs to a different session than the caller expects. */
    SESSION_CONTEXT_CHANGED
}

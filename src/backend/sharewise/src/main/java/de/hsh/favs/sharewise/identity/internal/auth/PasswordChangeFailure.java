package de.hsh.favs.sharewise.identity.internal.auth;

/** Domain-level password change denials; HTTP mapping belongs to Phase E. */
public enum PasswordChangeFailure {
    CURRENT_PASSWORD_INVALID,
    CREDENTIAL_CHANGED,
    ACCOUNT_UNAVAILABLE,
    SESSION_UNAVAILABLE
}

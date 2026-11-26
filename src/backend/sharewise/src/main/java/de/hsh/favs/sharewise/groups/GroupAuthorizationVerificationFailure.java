package de.hsh.favs.sharewise.groups;

/** Authoritative Group lookup outcomes. Anonymous callers fail through the IAM admission contract. */
public enum GroupAuthorizationVerificationFailure {
    UNKNOWN_USER,
    INELIGIBLE,
    UNAVAILABLE
}

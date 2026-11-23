package de.hsh.favs.sharewise.identity;

/** Expected failures of an authoritative IAM identity/session lookup. Neither permits a write. */
public enum IdentityVerificationFailure {
    /** IAM definitively found the user/session pair ineligible, without disclosing why. */
    INELIGIBLE,
    /** No authoritative decision was obtainable; the caller must fail closed. */
    UNAVAILABLE
}

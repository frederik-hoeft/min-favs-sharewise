package de.hsh.favs.sharewise.finance.internal.projection;

/** Successful result of applying a group-creation fact. */
public enum ActivationOutcome {
    /** The root, possibly provisioned in the same segment, moved from provisioning to open. */
    ACTIVATED,
    /** The root had already left provisioning; nothing was written. */
    UNCHANGED
}

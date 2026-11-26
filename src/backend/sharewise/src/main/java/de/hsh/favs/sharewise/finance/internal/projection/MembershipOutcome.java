package de.hsh.favs.sharewise.finance.internal.projection;

/**
 * Successful result of applying a membership fact. Every outcome means the fact is reflected in
 * committed Finance state; only {@link #INSERTED} and {@link #SUPERSEDED} wrote to it.
 */
public enum MembershipOutcome {
    /** The actor was previously unknown and is now retained, possibly as a removed tombstone. */
    INSERTED,
    /** The fact's revision was newer than the retained actor's, whose state and revision it replaced. */
    SUPERSEDED,
    /** The retained actor already has the fact's revision and state. */
    DUPLICATE,
    /** The retained actor already reflects a newer revision. */
    STALE
}

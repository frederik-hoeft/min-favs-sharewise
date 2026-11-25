package de.hsh.favs.sharewise.finance.internal.actor;

/**
 * Resulting organizational membership state of a retained Finance actor. A removed actor keeps
 * its identity so later financial records can still refer to it.
 */
public enum MembershipState {
    ACTIVE,
    REMOVED
}

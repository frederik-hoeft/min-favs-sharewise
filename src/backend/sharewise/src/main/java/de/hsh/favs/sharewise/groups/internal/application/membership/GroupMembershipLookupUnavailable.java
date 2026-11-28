package de.hsh.favs.sharewise.groups.internal.application.membership;

/** Persistence classified an operational outage rather than an authoritative membership denial. */
public final class GroupMembershipLookupUnavailable extends RuntimeException {
    public GroupMembershipLookupUnavailable(Throwable cause) {
        super("Group membership lookup unavailable", cause);
    }
}

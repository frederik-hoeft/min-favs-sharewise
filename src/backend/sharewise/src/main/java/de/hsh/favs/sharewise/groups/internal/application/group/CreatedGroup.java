package de.hsh.favs.sharewise.groups.internal.application.group;

import java.util.Objects;

/**
 * A newly created group together with its committed membership source revision.
 *
 * @param details the group as seen by its creator
 * @param membershipRevision the group's initial membership revision, which is the revision of the
 *     creator's owner membership
 */
public record CreatedGroup(GroupDetails details, long membershipRevision) {

    public CreatedGroup {
        Objects.requireNonNull(details, "details");
        if (membershipRevision < 1) {
            throw new IllegalArgumentException("membershipRevision must be positive");
        }
    }
}

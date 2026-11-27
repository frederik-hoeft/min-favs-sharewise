package de.hsh.favs.sharewise.groups.internal.application.invitation;

import de.hsh.favs.sharewise.groups.GroupRole;
import java.util.List;
import java.util.Objects;

/** One member-scoped snapshot, with invitation metadata only for administrators. */
public record InvitationListing(GroupRole callerRole, List<InvitationSummary> invitations) {
    public InvitationListing {
        Objects.requireNonNull(callerRole, "callerRole");
        invitations = List.copyOf(invitations);
    }
}

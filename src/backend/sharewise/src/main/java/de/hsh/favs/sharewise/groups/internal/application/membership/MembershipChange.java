package de.hsh.favs.sharewise.groups.internal.application.membership;

import de.hsh.favs.sharewise.groups.GroupRole;
import java.util.Objects;

/** The requested membership operation, independent of the caller's authorization. */
public sealed interface MembershipChange {
    record SetRole(GroupRole role) implements MembershipChange {
        public SetRole { Objects.requireNonNull(role, "role"); }
    }
    record Remove() implements MembershipChange { }
}

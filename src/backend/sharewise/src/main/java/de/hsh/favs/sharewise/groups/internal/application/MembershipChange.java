package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Objects;

/** The requested membership operation, independent of the caller's authorization. */
public sealed interface MembershipChange {
    record SetRole(GroupRole role) implements MembershipChange {
        public SetRole { Objects.requireNonNull(role, "role"); }
    }
    record Remove() implements MembershipChange { }
}

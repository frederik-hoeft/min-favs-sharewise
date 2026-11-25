package de.hsh.favs.sharewise.groups.internal.application;

import java.util.Objects;
import java.util.Optional;

/** Current caller, target and invariant facts from one post-lock statement.
 * The existence flags exclude the target and are false if the target is absent.
 */
public record MembershipChangeContext(GroupMember caller, Optional<GroupMember> target,
        boolean hasOtherMembers, boolean hasOtherAdministrators) {
    public MembershipChangeContext {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(target, "target");
    }
}

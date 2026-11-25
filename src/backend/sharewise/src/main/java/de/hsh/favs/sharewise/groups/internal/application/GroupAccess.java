package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Resolves live membership under the root lock in the caller's transaction segment. */
@Component
@Scope("operation")
class GroupAccess {
    private final GroupMutationPort groups;

    private final GroupMembershipPort members;

    GroupAccess(GroupMutationPort groups, GroupMembershipPort members) {
        this.members = Objects.requireNonNull(members, "members");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    Result<Access, GroupFailure> lockForMember(UUID groupId, UUID userId) {
        return groups.lockGroup(groupId).flatMap(group -> members.memberForUser(group, userId)
            .map(member -> new Access(group, member)))
            .<Result<Access, GroupFailure>>map(Result::ok)
            .orElseGet(() -> Result.err(new GroupFailure.GroupNotFound()));
    }

    record Access(LockedGroup group, GroupMember caller) {
        Result<Access, GroupFailure> requireAdministrator() {
            return caller.role().isAdministrator() ? Result.ok(this)
                : Result.err(new GroupFailure.AdministratorRequired());
        }
    }
}

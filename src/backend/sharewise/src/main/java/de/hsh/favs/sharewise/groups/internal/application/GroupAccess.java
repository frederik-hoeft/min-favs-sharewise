package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Resolves live membership under the root lock in the caller's transaction segment. */
@Component
@Scope("operation")
class GroupAccess {
    private final GroupMutationPort groups;
    private final GroupMembershipPort members;
    private final CallerAdmission admission;

    GroupAccess(GroupMutationPort groups, GroupMembershipPort members, CallerAdmission admission) {
        this.admission = Objects.requireNonNull(admission);
        this.members = Objects.requireNonNull(members, "members");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    Result<Access, GroupFailure> lockForMember(UUID groupId, UUID userId) {
        return lockAndRead(groupId, userId, group -> members.memberForUser(group, userId))
            .map(locked -> new Access(locked.group(), locked.context()));
    }

    Result<LockedContext<MembershipChangeContext>, GroupFailure> lockForChange(UUID groupId, UUID userId,
            UUID membershipId) {
        return lockAndRead(groupId, userId, group -> members.changeContext(group, userId, membershipId));
    }

    private <T> Result<LockedContext<T>, GroupFailure> lockAndRead(UUID groupId, UUID userId,
            Function<LockedGroup, Optional<T>> read) {
        // The root selection establishes local existence; the separate read has a fresh snapshot.
        return admission.resolveProjection(groups.lockGroup(groupId, userId), new GroupFailure.GroupNotFound())
            .flatMap(group -> read.apply(group)
                .<Result<LockedContext<T>, GroupFailure>>map(context -> Result.ok(new LockedContext<>(group, context)))
                .orElseGet(() -> Result.err(new GroupFailure.GroupNotFound())));
    }

    record LockedContext<T>(LockedGroup group, T context) {}

    record Access(LockedGroup group, GroupMember caller) {
        Result<Access, GroupFailure> requireAdministrator() {
            return caller.role().isAdministrator() ? Result.ok(this)
                : Result.err(new GroupFailure.AdministratorRequired());
        }
    }
}

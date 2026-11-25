package de.hsh.favs.sharewise.groups.internal.application.membership;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.application.access.GroupLockPort;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
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
public class GroupAccess {
    private final GroupLockPort groups;
    private final GroupMembershipPort members;
    private final CallerAdmission admission;

    GroupAccess(GroupLockPort groups, GroupMembershipPort members, CallerAdmission admission) {
        this.admission = Objects.requireNonNull(admission);
        this.members = Objects.requireNonNull(members, "members");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    public Result<Access, GroupFailure> lockForMember(UUID groupId, UUID userId) {
        return lockAndRead(groupId, userId, group -> members.memberForUser(group, userId))
            .map(locked -> new Access(locked.group(), locked.context()));
    }

    Result<LockedContext<MembershipChangeContext>, GroupFailure> lockForChange(UUID groupId, UUID userId,
            UUID membershipId) {
        return lockAndRead(groupId, userId, group -> members.changeContext(group, userId, membershipId));
    }

    private <T> Result<LockedContext<T>, GroupFailure> lockAndRead(UUID groupId, UUID userId,
            Function<LockedGroup, Optional<T>> read) {
        // Membership filters lock admission; only the separate read establishes live authorization.
        return admission.resolveProjection(groups.lockGroupForMember(groupId, userId), new GroupFailure.GroupNotFound())
            .flatMap(group -> read.apply(group)
                .<Result<LockedContext<T>, GroupFailure>>map(context -> Result.ok(new LockedContext<>(group, context)))
                .orElseGet(() -> Result.err(new GroupFailure.GroupNotFound())));
    }

    record LockedContext<T>(LockedGroup group, T context) {}

    public record Access(LockedGroup group, GroupMember caller) {
        public Result<Access, GroupFailure> requireAdministrator() {
            return caller.role().isAdministrator() ? Result.ok(this)
                : Result.err(new GroupFailure.AdministratorRequired());
        }
    }
}

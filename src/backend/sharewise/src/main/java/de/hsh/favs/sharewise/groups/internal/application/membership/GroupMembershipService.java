package de.hsh.favs.sharewise.groups.internal.application.membership;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.GroupOutcomes;
import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Manages current membership while preserving at least one member and administrator. */
@Component
@Scope("operation")
public class GroupMembershipService {
    private final CallerAdmission admission;
    private final GroupAccess access;
    private final GroupMembershipPort members;
    private final Transactions transactions;

    GroupMembershipService(CallerAdmission admission, GroupAccess access, GroupMembershipPort members,
            Transactions transactions) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.access = Objects.requireNonNull(access, "access");
        this.members = Objects.requireNonNull(members, "members");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Result<MembershipChangeStatus, GroupFailure> change(MembershipChangeCommand command) {
        Objects.requireNonNull(command, "command");
        UUID userId = admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.mutation(access.lockForChange(
            command.groupId(), userId, command.membershipId())
            .flatMap(locked -> changeLocked(locked.group(), locked.context(), command))));
    }

    private Result<MembershipChangeStatus, GroupFailure> changeLocked(LockedGroup group, MembershipChangeContext context,
            MembershipChangeCommand command) {
        GroupMember caller = context.caller();
        boolean self = caller.id().equals(command.membershipId());
        if (!caller.role().isAdministrator() && !(self && command.change() instanceof MembershipChange.Remove)) {
            return Result.err(new GroupFailure.AdministratorRequired());
        }
        Optional<GroupMember> target = context.target();
        if (target.isEmpty()) {
            return Result.err(new GroupFailure.MemberNotFound());
        }
        GroupMember member = target.get();
        return switch (command.change()) {
            case MembershipChange.SetRole(GroupRole role) -> self && member.role() != role
                ? Result.err(new GroupFailure.SelfRoleChange()) : setRole(group, member, role, context.hasOtherAdministrators());
            case MembershipChange.Remove() -> remove(group, member, context);
        };
    }

    private Result<MembershipChangeStatus, GroupFailure> setRole(LockedGroup group, GroupMember member, GroupRole role, boolean hasOtherAdministrators) {
        if (member.role() == role) {
            return completed();
        }
        if (member.role().isAdministrator() && !hasOtherAdministrators) {
            return Result.err(new GroupFailure.LastAdministrator());
        }
        members.setRole(group, member.id(), role);
        return completed();
    }

    private Result<MembershipChangeStatus, GroupFailure> remove(LockedGroup group, GroupMember member, MembershipChangeContext context) {
        if (!context.hasOtherMembers()) {
            return Result.err(new GroupFailure.LastMember());
        }
        if (member.role().isAdministrator() && !context.hasOtherAdministrators()) {
            return Result.err(new GroupFailure.LastAdministrator());
        }
        members.removeMember(group, member.id());
        return completed();
    }

    private static Result<MembershipChangeStatus, GroupFailure> completed() {
        return Result.ok(MembershipChangeStatus.COMPLETED);
    }
}

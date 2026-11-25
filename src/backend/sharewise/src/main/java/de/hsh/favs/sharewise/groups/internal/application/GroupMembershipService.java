package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;
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
        admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.mutation(admission.admit()
            .flatMap(caller -> access.lockForMember(command.groupId(), caller.userId()))
            .flatMap(locked -> changeLocked(locked, command))));
    }

    private Result<MembershipChangeStatus, GroupFailure> changeLocked(GroupAccess.Access locked,
            MembershipChangeCommand command) {
        GroupMember caller = locked.caller();
        boolean self = caller.id().equals(command.membershipId());
        if (!caller.role().isAdministrator() && !(self && command.change() instanceof MembershipChange.Remove)) {
            return Result.err(new GroupFailure.AdministratorRequired());
        }
        Optional<GroupMember> target = self ? Optional.of(caller)
            : members.memberById(locked.group(), command.membershipId());
        if (target.isEmpty()) {
            return Result.err(new GroupFailure.MemberNotFound());
        }
        GroupMember member = target.get();
        return switch (command.change()) {
            case MembershipChange.SetRole(GroupRole role) -> setRole(locked.group(), member, role);
            case MembershipChange.Remove() -> remove(locked.group(), member);
            case MembershipChange.TransferAdministration() -> transfer(locked.group(), caller, member);
        };
    }

    private Result<MembershipChangeStatus, GroupFailure> setRole(LockedGroup group, GroupMember member, GroupRole role) {
        if (member.role() == role) {
            return completed();
        }
        if (isLastAdministrator(group, member)) {
            return Result.err(new GroupFailure.LastAdministrator());
        }
        members.setRole(group, member.id(), role);
        return completed();
    }

    private Result<MembershipChangeStatus, GroupFailure> remove(LockedGroup group, GroupMember member) {
        if (members.countMembers(group, Optional.empty()) <= 1) {
            return Result.err(new GroupFailure.LastMember());
        }
        if (isLastAdministrator(group, member)) {
            return Result.err(new GroupFailure.LastAdministrator());
        }
        members.removeMember(group, member.id());
        return completed();
    }

    private Result<MembershipChangeStatus, GroupFailure> transfer(LockedGroup group, GroupMember caller,
            GroupMember target) {
        if (caller.id().equals(target.id())) {
            return Result.err(new GroupFailure.InvalidTransfer());
        }
        if (!target.role().isAdministrator()) {
            members.setRole(group, target.id(), GroupRole.ADMIN);
        }
        members.setRole(group, caller.id(), GroupRole.MEMBER);
        return completed();
    }

    private boolean isLastAdministrator(LockedGroup group, GroupMember member) {
        return member.role().isAdministrator() && members.countMembers(group, Optional.of(GroupRole.ADMIN)) <= 1;
    }

    private static Result<MembershipChangeStatus, GroupFailure> completed() {
        return Result.ok(MembershipChangeStatus.COMPLETED);
    }
}

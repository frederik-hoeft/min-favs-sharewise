package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMember;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A single member-authorized snapshot of group metadata and the current roster. */
public record GroupWorkspace(GroupDetails group, UUID callerMembershipId, List<GroupMember> members) {
    public GroupWorkspace {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(callerMembershipId, "callerMembershipId");
        members = List.copyOf(members);
        if (members.size() != group.memberCount() || members.stream().noneMatch(member ->
                member.id().equals(callerMembershipId) && member.role() == group.callerRole())) {
            throw new IllegalArgumentException("Inconsistent workspace projection");
        }
    }
}

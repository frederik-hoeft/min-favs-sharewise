package de.hsh.favs.sharewise.groups.internal.application;

import java.util.Objects;
import java.util.UUID;

public record MembershipChangeCommand(UUID groupId, UUID membershipId, MembershipChange change) {
    public MembershipChangeCommand {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(change, "change");
    }
}

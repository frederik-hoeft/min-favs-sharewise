package de.hsh.favs.sharewise.groups.internal.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CreateInvitationCommand(UUID groupId, Instant expiresAt, Integer maxUses) {
    public CreateInvitationCommand {
        Objects.requireNonNull(groupId, "groupId");
    }
}

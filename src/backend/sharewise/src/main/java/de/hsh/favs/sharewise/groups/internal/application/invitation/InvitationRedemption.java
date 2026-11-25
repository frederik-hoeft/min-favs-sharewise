package de.hsh.favs.sharewise.groups.internal.application.invitation;

import java.util.UUID;

public record InvitationRedemption(UUID groupId, Outcome outcome) {
    public enum Outcome { JOINED, ALREADY_MEMBER }
}

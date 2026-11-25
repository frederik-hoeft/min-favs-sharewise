package de.hsh.favs.sharewise.groups.internal.application.invitation;

import java.time.Instant;
import java.util.UUID;

/** Nonsecret invitation projection. Nullable constraints represent unlimited validity/use. */
public record InvitationSummary(UUID id, Instant createdAt, Instant expiresAt, Integer maxUses, int useCount) {
    boolean availableAt(Instant now) {
        return (expiresAt == null || now.isBefore(expiresAt))
            && (maxUses == null || useCount < maxUses) && useCount < Integer.MAX_VALUE;
    }
}

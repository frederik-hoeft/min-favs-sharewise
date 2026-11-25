package de.hsh.favs.sharewise.groups.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invitation persistence. All scoped operations require the group-root lock in the current segment. */
public interface GroupInvitationPort {
    /** Preliminary selector lookup only, never authorization. */
    Optional<UUID> groupIdForSelector(UUID selector);

    /** Database wall-clock time, not the start of the transaction. */
    Instant currentTime();

    UUID newInvitationId();

    List<InvitationSummary> list(LockedGroup group);

    Optional<StoredInvitation> find(LockedGroup group, UUID selector);

    InvitationSummary insert(LockedGroup group, UUID selector, byte[] verifier, Instant createdAt,
        Instant expiresAt, Integer maxUses);

    boolean revoke(LockedGroup group, UUID selector);

    /** Increment exactly one invitation previously verified under the root lock, or throw. */
    void consume(LockedGroup group, UUID selector);
}

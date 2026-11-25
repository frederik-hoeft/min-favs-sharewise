package de.hsh.favs.sharewise.groups.internal.application.invitation;

import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Invitation persistence; mutations and redemption facts require the group-root lock. */
public interface GroupInvitationPort {
    /** Member-scoped snapshot; only administrators receive invitation metadata. */
    Optional<InvitationListing> listForMember(UUID groupId, UUID userId);

    /** Read-only, known-user-scoped snapshot; the application must verify the bearer. */
    Optional<InvitationPreviewContext> previewContext(UUID selector, UUID userId);

    /** Locks only the group root selected by the invitation for a known local user. Not authorization. */
    Optional<LockedGroup> lockGroupForRedemption(UUID selector, UUID userId);

    /** Fresh post-lock invitation, database wall-clock time and membership existence. */
    Optional<InvitationRedemptionContext> redemptionContext(LockedGroup group, UUID selector, UUID userId);

    /** Database wall-clock time and UUIDv7 from one statement under the root lock. */
    InvitationIssuanceMaterial issuanceMaterial(LockedGroup group);

    InvitationSummary insert(LockedGroup group, UUID selector, byte[] verifier, Instant createdAt,
        Instant expiresAt, Integer maxUses);

    boolean revoke(LockedGroup group, UUID selector);

    /** Increment exactly one invitation previously verified under the root lock, or throw. */
    void consume(LockedGroup group, UUID selector);
}

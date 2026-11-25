package de.hsh.favs.sharewise.groups.internal.application.invitation;

import java.time.Instant;

/** Verification material and current facts evaluated after locking the group root. */
public record InvitationRedemptionContext(StoredInvitation invitation, Instant evaluatedAt, boolean alreadyMember) {}

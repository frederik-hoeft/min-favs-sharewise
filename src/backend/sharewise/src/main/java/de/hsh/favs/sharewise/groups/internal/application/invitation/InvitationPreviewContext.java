package de.hsh.favs.sharewise.groups.internal.application.invitation;

import java.time.Instant;

/** Snapshot of the token verification facts and the invited group's presentation name. */
public record InvitationPreviewContext(StoredInvitation invitation, Instant evaluatedAt, String groupName) { }

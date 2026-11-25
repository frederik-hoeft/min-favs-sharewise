package de.hsh.favs.sharewise.groups.internal.application.invitation;

import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator.GeneratedToken;

/** Only returned after the creation transaction has committed. */
public record CreatedInvitation(InvitationSummary invitation, GeneratedToken token) { }

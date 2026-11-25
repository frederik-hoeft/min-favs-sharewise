package de.hsh.favs.sharewise.groups.internal.application;

import java.time.Instant;
import java.util.UUID;

/** Database-generated issuance identity and wall-clock timestamp. */
public record InvitationIssuanceMaterial(UUID selector, Instant createdAt) {}

package de.hsh.favs.sharewise.identity.internal.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Replacement of the required display name. */
public record AccountProfileUpdateRequest(@JsonProperty(required = true) String displayName) { }

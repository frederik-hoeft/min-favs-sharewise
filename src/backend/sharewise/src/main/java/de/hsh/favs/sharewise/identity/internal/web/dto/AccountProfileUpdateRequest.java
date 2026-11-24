package de.hsh.favs.sharewise.identity.internal.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Profile update request; the clear flag must agree with the supplied display name. */
public record AccountProfileUpdateRequest(
        @JsonProperty(required = true) String displayName,
        @JsonProperty(required = true) boolean clear) {
}

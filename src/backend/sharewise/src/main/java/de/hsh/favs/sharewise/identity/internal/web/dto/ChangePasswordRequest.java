package de.hsh.favs.sharewise.identity.internal.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Password-change transport payload. Never expose either credential through diagnostics. */
public record ChangePasswordRequest(
        @JsonProperty(required = true) String currentPassword,
        @JsonProperty(required = true) String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[credentials redacted]";
    }
}

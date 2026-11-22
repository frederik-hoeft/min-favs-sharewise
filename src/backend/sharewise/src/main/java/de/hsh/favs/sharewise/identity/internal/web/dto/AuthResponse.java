package de.hsh.favs.sharewise.identity.internal.web.dto;

import java.time.Instant;

/** Credential issuance response. Redact bearer values from diagnostics. */
public record AuthResponse(String accessToken, String tokenType, Instant expiresAt) {
    @Override
    public String toString() {
        return "AuthResponse[redacted]";
    }
}

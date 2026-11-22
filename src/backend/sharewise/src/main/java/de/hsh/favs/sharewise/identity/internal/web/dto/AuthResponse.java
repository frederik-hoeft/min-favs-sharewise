package de.hsh.favs.sharewise.identity.internal.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Credential issuance response. The refresh secret is exclusively an HttpOnly cookie.
 *
 * @param accessToken signed primary bearer JWT returned only after committed authentication
 * @param tokenType OAuth-compatible token type, always {@code Bearer}
 * @param expiresAt authoritative server-issued access JWT expiration
 * @param sessionId stable session identity retained across refresh generations
 */
public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UUID sessionId) {
    @Override
    public String toString() {
        return "AuthResponse[redacted]";
    }
}

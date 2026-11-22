package de.hsh.favs.sharewise.identity.internal.web.dto;

/** Credential entry payload. Never include the submitted password in diagnostics. */
public record AuthCredentialsRequest(String email, String password) {
    @Override
    public String toString() {
        return "AuthCredentialsRequest[redacted]";
    }
}

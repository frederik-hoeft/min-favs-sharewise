package de.hsh.favs.sharewise.identity.internal.web.dto;

/** Registration input; never print submitted credentials. */
public record RegistrationRequest(String email, String password, String displayName) {
    @Override public String toString() { return "RegistrationRequest[redacted]"; }
}

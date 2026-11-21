package de.hsh.favs.sharewise.identity.internal.web.dto;

/** Masked CSRF request token returned to the same-origin browser. */
public record CsrfBootstrap(String token) {
    @Override
    public String toString() {
        return "CsrfBootstrap[redacted]";
    }
}

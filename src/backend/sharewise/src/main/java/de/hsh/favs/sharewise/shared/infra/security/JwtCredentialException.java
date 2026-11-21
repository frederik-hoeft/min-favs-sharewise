package de.hsh.favs.sharewise.shared.infra.security;

/**
 * Invalid, untrusted, expired or improperly bound JWT evidence.
 *
 * <p>Messages deliberately contain no token, JWT payload, signature or key material.
 */
public final class JwtCredentialException extends RuntimeException {
    /** Creates a sanitized verification error. */
    public JwtCredentialException() {
        super("JWT credential verification failed");
    }
}

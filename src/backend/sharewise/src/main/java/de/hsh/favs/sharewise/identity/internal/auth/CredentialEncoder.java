package de.hsh.favs.sharewise.identity.internal.auth;

/** IAM-private adapter boundary; production uses Spring Security PasswordEncoder in Phase E. */
public interface CredentialEncoder {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
}

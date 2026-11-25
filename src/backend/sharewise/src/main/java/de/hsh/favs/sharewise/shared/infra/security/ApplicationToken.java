package de.hsh.favs.sharewise.shared.infra.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Selector and verifier of an opaque application token; never retains the bearer secret. */
public final class ApplicationToken {
    public static final int SECRET_BYTES = 32;
    public static final int VERIFIER_BYTES = 32;

    private final UUID selector;
    private final byte[] verifierHash;

    ApplicationToken(UUID selector, byte[] verifierHash) {
        this.selector = Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(verifierHash, "verifierHash");
        if (verifierHash.length != VERIFIER_BYTES) {
            throw new IllegalArgumentException("Invalid application token verifier length");
        }
        this.verifierHash = verifierHash.clone();
    }

    public UUID selector() { return selector; }

    /** Both the selector and the fixed-length SHA-256 verifier must match. */
    public boolean matches(UUID storedSelector, byte[] storedVerifier) {
        return selector.equals(storedSelector) && storedVerifier != null
                && storedVerifier.length == VERIFIER_BYTES
                && MessageDigest.isEqual(verifierHash, storedVerifier);
    }

    static byte[] verifierFor(byte[] secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 provider is unavailable", failure);
        }
    }

    @Override
    public String toString() { return "ApplicationToken[redacted]"; }
}

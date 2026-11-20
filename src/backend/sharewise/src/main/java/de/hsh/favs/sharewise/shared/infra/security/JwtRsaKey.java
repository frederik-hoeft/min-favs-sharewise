package de.hsh.favs.sharewise.shared.infra.security;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

/**
 * Explicitly trusted local RSA public key, optionally paired with signing material.
 *
 * <p>Private keys are never exposed through record accessors or diagnostics.
 * Key selection is restricted to configured IDs; no URL or key embedded in a JWT
 * is consulted. A configured key must use at least 2048 RSA bits.
 */
public final class JwtRsaKey {
    private final String id;
    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    private JwtRsaKey(String id, RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("JWT key ID must not be blank");
        }
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        if (publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("JWT RSA key must be at least 2048 bits");
        }
        this.privateKey = privateKey;
    }

    /**
     * Creates a verification-only key.
     *
     * @param id unique, configured key ID
     * @param key trusted RSA public key
     * @return a verification-only key descriptor
     */
    public static JwtRsaKey verification(String id, RSAPublicKey key) {
        return new JwtRsaKey(id, key, null);
    }

    /**
     * Creates a key for both verification and signing.
     *
     * @param id unique, configured key ID
     * @param pair RSA public/private key pair
     * @return a signing-capable key descriptor
     */
    public static JwtRsaKey signing(String id, KeyPair pair) {
        Objects.requireNonNull(pair, "pair");
        if (!(pair.getPublic() instanceof RSAPublicKey publicKey)
            || !(pair.getPrivate() instanceof RSAPrivateKey privateKey)) {
            throw new IllegalArgumentException("JWT signing requires an RSA key pair");
        }
        return new JwtRsaKey(id, publicKey, privateKey);
    }

    /**
     * Returns the configured opaque key identifier.
     *
     * @return the key ID
     */
    public String id() {
        return id;
    }

    RSAPublicKey publicKey() {
        return publicKey;
    }

    RSAPrivateKey privateKey() {
        return privateKey;
    }

    @Override
    public String toString() {
        return "JwtRsaKey[id=" + id + ", signing=" + (privateKey != null) + "]";
    }
}

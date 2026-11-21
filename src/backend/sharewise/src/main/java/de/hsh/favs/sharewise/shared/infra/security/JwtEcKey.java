package de.hsh.favs.sharewise.shared.infra.security;

import com.nimbusds.jose.jwk.Curve;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Objects;

/**
 * Trusted ES256 (NIST P-256) key descriptor with optional signing material.
 *
 * <p>Key material is supplied locally; no JWK from an untrusted JWT can configure
 * this key. Private material is neither publicly exposed nor printed in diagnostics.
 */
public final class JwtEcKey {
    private final String id;
    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;

    private JwtEcKey(String id, ECPublicKey publicKey, ECPrivateKey privateKey) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("JWT key ID must not be blank");
        }
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        if (!Curve.P_256.equals(Curve.forECParameterSpec(publicKey.getParams()))
            || (privateKey != null && !Curve.P_256.equals(Curve.forECParameterSpec(privateKey.getParams())))) {
            throw new IllegalArgumentException("ES256 requires NIST P-256 keys");
        }
        this.privateKey = privateKey;
    }

    /**
     * Creates a verification-only key descriptor.
     *
     * @param id unique configured key ID
     * @param publicKey trusted P-256 public key
     * @return verification-only descriptor
     */
    public static JwtEcKey verification(String id, ECPublicKey publicKey) {
        return new JwtEcKey(id, publicKey, null);
    }

    /**
     * Creates a signing-capable key descriptor.
     *
     * @param id unique configured key ID
     * @param pair matching P-256 public/private key pair
     * @return signing-capable descriptor
     */
    public static JwtEcKey signing(String id, KeyPair pair) {
        Objects.requireNonNull(pair, "pair");
        if (!(pair.getPublic() instanceof ECPublicKey publicKey)
            || !(pair.getPrivate() instanceof ECPrivateKey privateKey)) {
            throw new IllegalArgumentException("ES256 requires an EC key pair");
        }
        JwtEcKey key = new JwtEcKey(id, publicKey, privateKey);
        // Detect misprovisioned pairs at configuration time rather than issuing
        // credentials that no verifier can accept.
        try {
            Signature proof = Signature.getInstance("SHA256withECDSA");
            proof.initSign(privateKey);
            proof.update(new byte[] {0x53, 0x57, 0x2d, 0x4a, 0x57, 0x54});
            byte[] signed = proof.sign();
            proof.initVerify(publicKey);
            proof.update(new byte[] {0x53, 0x57, 0x2d, 0x4a, 0x57, 0x54});
            if (!proof.verify(signed)) {
                throw new IllegalArgumentException("JWT EC key pair does not match");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("JWT EC key pair cannot sign and verify", exception);
        }
        return key;
    }

    /**
     * Returns the configured opaque key ID.
     *
     * @return key ID
     */
    public String id() {
        return id;
    }

    ECPublicKey publicKey() {
        return publicKey;
    }

    ECPrivateKey privateKey() {
        return privateKey;
    }

    @Override
    public String toString() {
        return "JwtEcKey[id=" + id + ", signing=" + (privateKey != null) + "]";
    }
}

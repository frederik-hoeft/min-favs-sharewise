package de.hsh.favs.sharewise.shared.infra.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Locally provisioned public keys with at most one explicitly active private key. */
public final class JwtPemKeyConfiguration {
    private JwtPemKeyConfiguration() { }

    public record Key(String id, String publicKeyFile, String privateKeyFile) { }

    public static Es256JwtSignatureService load(List<Key> configured, Optional<String> activeKeyId) {
        Objects.requireNonNull(configured);
        Objects.requireNonNull(activeKeyId);
        List<JwtEcKey> keys = new ArrayList<>();
        int active = 0;
        for (Key key : configured) {
            if (activeKeyId.filter(id -> id.equals(key.id())).isPresent()) {
                active++;
                keys.add(EcPemKeyLoader.signing(key.id(), key.publicKeyFile(), key.privateKeyFile()));
            } else {
                if (key.privateKeyFile() != null && !key.privateKeyFile().isBlank()) {
                    throw new IllegalStateException("Only the active JWT signing key may contain private material");
                }
                keys.add(EcPemKeyLoader.verification(key.id(), key.publicKeyFile()));
            }
        }
        if (activeKeyId.isPresent() && active != 1) {
            throw new IllegalStateException("Exactly one active JWT signing key must be configured");
        }
        return new Es256JwtSignatureService(keys, activeKeyId);
    }
}

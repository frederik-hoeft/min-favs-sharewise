package de.hsh.favs.sharewise.groups.internal.authorization;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local Group trust and optional active signing configuration. */
@ConfigurationProperties(prefix = "sharewise.groups.attestation")
record GroupAttestationSettings(String issuer, String audience, String trustedPrimaryIssuer,
        Duration clockSkew, String activeKeyId, List<Key> keys) {
    GroupAttestationSettings {
        audience = audience == null ? "sw-api-v1" : audience;
        clockSkew = clockSkew == null ? Duration.ofSeconds(15) : clockSkew;
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
    record Key(String id, String publicKeyFile, String privateKeyFile) { }
}

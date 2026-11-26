package de.hsh.favs.sharewise.groups.internal.authorization;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local, verification-only trust settings; issuance and provisioning are added in Phase C. */
@ConfigurationProperties(prefix = "sharewise.groups.attestation")
record GroupAttestationSettings(String issuer, String audience, String trustedPrimaryIssuer,
        Duration clockSkew, List<Key> keys) {
    GroupAttestationSettings {
        audience = audience == null ? "sw-api-v1" : audience;
        clockSkew = clockSkew == null ? Duration.ofSeconds(15) : clockSkew;
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
    record Key(String id, String publicKeyFile) { }
}

package de.hsh.favs.sharewise.identity.internal.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-supplied IAM credential configuration. No signing secrets ship with the application. */
@ConfigurationProperties(prefix = "sharewise.iam.auth")
public record IamAuthenticationSettings(
        String issuer, String audience, String activeKeyId, List<Key> keys,
        String blocklistFile, String blocklistVersion, String blocklistSha256,
        int maxConcurrentHashes) {

    public record Key(String id, String publicKeyFile, String privateKeyFile) { }
}

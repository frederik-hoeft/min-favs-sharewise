package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.shared.infra.security.EcPemKeyLoader;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialVerifier;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Verification trust is explicitly provisioned; no implicit development keys or issuer discovery. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sharewise.groups.attestation", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GroupAttestationSettings.class)
class GroupAttestationConfiguration {
    @Bean
    GroupMembershipTrust groupMembershipTrust(GroupAttestationSettings settings) {
        var profile = GroupMembershipTrust.profile(settings.issuer(), settings.audience(),
            settings.trustedPrimaryIssuer(), settings.clockSkew());
        var keys = settings.keys().stream()
            .map(key -> EcPemKeyLoader.verification(key.id(), key.publicKeyFile())).toList();
        return new GroupMembershipTrust(new JwtCredentialCodec(profile,
            new Es256JwtSignatureService(keys, Optional.empty()), Clock.systemUTC()));
    }

    @Bean
    GroupCredentialValidator groupCredentialValidator(GroupMembershipTrust trust) {
        return new GroupCredentialValidator(trust);
    }

    @Bean
    SupplementaryCredentialVerifier groupMembershipVerifier(GroupMembershipTrust trust) {
        return new SupplementaryCredentialVerifier(trust.codec());
    }
}

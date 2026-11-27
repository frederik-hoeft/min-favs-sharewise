package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.shared.infra.security.JwtPemKeyConfiguration;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import org.springframework.beans.factory.annotation.Qualifier;
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
    GroupMembershipTrust groupMembershipTrust(GroupAttestationSettings settings,
            @Qualifier("groupAuthorizationClock") Clock clock) {
        JwtCredentialProfile profile = GroupMembershipTrust.profile(settings.issuer(), settings.audience(),
            settings.trustedPrimaryIssuer(), settings.clockSkew());
        Optional<String> active = Optional.ofNullable(settings.activeKeyId());
        return new GroupMembershipTrust(new JwtCredentialCodec(profile,
            JwtPemKeyConfiguration.load(settings.keys().stream().map(key -> new JwtPemKeyConfiguration.Key(
                key.id(), key.publicKeyFile(), key.privateKeyFile())).toList(), active), clock), active.isPresent());
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

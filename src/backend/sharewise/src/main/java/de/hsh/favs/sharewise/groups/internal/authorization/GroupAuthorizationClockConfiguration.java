package de.hsh.favs.sharewise.groups.internal.authorization;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GroupAuthorizationClockConfiguration {
    // Qualifier-only, like IAM's clock: unqualified Clock lookups must not become ambiguous.
    @Bean(name = "groupAuthorizationClock", defaultCandidate = false)
    Clock groupAuthorizationClock() { return Clock.systemUTC(); }
}

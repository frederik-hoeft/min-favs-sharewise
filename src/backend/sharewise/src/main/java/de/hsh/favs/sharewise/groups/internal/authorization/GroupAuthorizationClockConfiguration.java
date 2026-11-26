package de.hsh.favs.sharewise.groups.internal.authorization;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GroupAuthorizationClockConfiguration {
    @Bean("groupAuthorizationClock")
    Clock groupAuthorizationClock() { return Clock.systemUTC(); }
}

package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Recovery for event publications (P2.4a, D9): scheduling for Spring Modulith's staleness
 * monitor and the backed-off resubmission of failed publications. Restart republication and
 * staleness durations are configured in {@code application.properties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EventResubmissionSettings.class)
class EventPublicationRecoveryConfiguration {

    @Bean
    FailedPublicationResubmitter failedPublicationResubmitter(
            ObjectProvider<FailedEventPublications> failedPublications, EventResubmissionSettings settings) {
        return new FailedPublicationResubmitter(failedPublications, settings, Clock.systemUTC());
    }
}

package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded, backed-off resubmission of failed event publications (P2.4a, D9).
 *
 * @param interval delay between resubmission runs
 * @param initialBackoff wait after the first failed attempt; doubles with every further attempt
 * @param maxBackoff upper bound for the wait between attempts
 * @param batchSize maximum publications considered per run
 * @param maxInFlight maximum resubmitted publications processed concurrently
 */
@ConfigurationProperties(prefix = "sharewise.events.resubmission")
public record EventResubmissionSettings(Duration interval, Duration initialBackoff, Duration maxBackoff,
        Integer batchSize, Integer maxInFlight) {

    public EventResubmissionSettings {
        interval = interval == null ? Duration.ofMinutes(1) : interval;
        initialBackoff = initialBackoff == null ? Duration.ofMinutes(1) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(30) : maxBackoff;
        batchSize = batchSize == null ? 50 : batchSize;
        maxInFlight = maxInFlight == null ? 4 : maxInFlight;
        if (interval.isNegative() || interval.isZero() || initialBackoff.isNegative()
                || maxBackoff.compareTo(initialBackoff) < 0 || batchSize < 1 || maxInFlight < 1) {
            throw new IllegalArgumentException("Invalid event resubmission configuration");
        }
    }
}

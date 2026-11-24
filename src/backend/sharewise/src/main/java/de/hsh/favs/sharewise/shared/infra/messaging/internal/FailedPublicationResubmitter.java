package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically resubmits failed event publications with exponential backoff (P2.4a, D9).
 *
 * <p>Runs on the scheduler thread, outside any transaction, so the asynchronous listeners it
 * triggers can start their own {@code Transactions} segments. Publications are never abandoned:
 * a publication that keeps failing is retried at most every {@code maxBackoff} and stays visible
 * in {@code messaging.event_publication} for operators. Inactive when the registry is absent
 * (database-free component tests).
 */
class FailedPublicationResubmitter {

    private static final Logger LOG = LoggerFactory.getLogger(FailedPublicationResubmitter.class);

    private final ObjectProvider<FailedEventPublications> failedPublications;
    private final EventResubmissionSettings settings;
    private final Clock clock;

    FailedPublicationResubmitter(ObjectProvider<FailedEventPublications> failedPublications,
            EventResubmissionSettings settings, Clock clock) {
        this.failedPublications = Objects.requireNonNull(failedPublications, "failedPublications");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${sharewise.events.resubmission.interval:PT1M}",
            initialDelayString = "${sharewise.events.resubmission.interval:PT1M}")
    void resubmitDueFailures() {
        FailedEventPublications failed = failedPublications.getIfAvailable();
        if (failed == null) {
            return;
        }
        Instant now = clock.instant();
        failed.resubmit(ResubmissionOptions.defaults()
                .withBatchSize(settings.batchSize())
                .withMaxInFlight(settings.maxInFlight())
                .withFilter(publication -> select(publication, now)));
    }

    private boolean select(EventPublication publication, Instant now) {
        Instant lastAttempt = publication.getLastResubmissionDate() == null
                ? publication.getPublicationDate()
                : publication.getLastResubmissionDate();
        boolean due = isDue(publication.getCompletionAttempts(), lastAttempt, now,
                settings.initialBackoff(), settings.maxBackoff());
        if (due) {
            LOG.info("Resubmitting failed event publication {} ({}) after {} attempt(s)",
                    publication.getIdentifier(), publication.getEvent().getClass().getName(),
                    publication.getCompletionAttempts());
        }
        return due;
    }

    /**
     * Exponential backoff: after {@code attempts} failed attempts, wait
     * {@code initialBackoff * 2^(attempts - 1)}, capped at {@code maxBackoff}.
     */
    static boolean isDue(int attempts, Instant lastAttempt, Instant now, Duration initialBackoff,
            Duration maxBackoff) {
        Duration wait;
        if (attempts <= 1) {
            wait = initialBackoff;
        } else {
            int exponent = Math.min(attempts - 1, 30);
            Duration grown = initialBackoff.multipliedBy(1L << exponent);
            wait = grown.compareTo(maxBackoff) > 0 || grown.isNegative() ? maxBackoff : grown;
        }
        return !now.isBefore(lastAttempt.plus(wait));
    }
}

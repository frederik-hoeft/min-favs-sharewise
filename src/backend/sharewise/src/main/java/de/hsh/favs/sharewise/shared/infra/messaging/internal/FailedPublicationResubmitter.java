package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
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
        failed.resubmit(options(settings, clock.instant()));
    }

    /**
     * Spring Modulith reads at most {@code min(batchSize, maxInFlight - RESUBMITTED)} failed rows,
     * oldest first, and only then applies the filter. Passing our small {@code maxInFlight} there
     * would let a few old, not-yet-due publications occupy every slot and starve newer ones. The
     * framework therefore reads up to {@code batchSize} rows, and the filter accepts at most
     * {@code maxInFlight} due publications per run. Concurrency is additionally bounded by the
     * application task executor.
     */
    static ResubmissionOptions options(EventResubmissionSettings settings, Instant now) {
        AtomicInteger accepted = new AtomicInteger();
        return ResubmissionOptions.defaults()
                .withBatchSize(settings.batchSize())
                .withMaxInFlight(settings.batchSize())
                .withFilter(publication -> select(publication, now, settings, accepted));
    }

    private static boolean select(EventPublication publication, Instant now, EventResubmissionSettings settings,
            AtomicInteger accepted) {
        if (accepted.get() >= settings.maxInFlight()) {
            return false;
        }
        Instant lastAttempt = publication.getLastResubmissionDate() == null
                ? publication.getPublicationDate()
                : publication.getLastResubmissionDate();
        if (!isDue(publication.getCompletionAttempts(), lastAttempt, now, settings.initialBackoff(),
                settings.maxBackoff())) {
            return false;
        }
        accepted.incrementAndGet();
        // Never deserialize the payload here: an incompatible event would abort the whole run.
        LOG.info("Resubmitting failed event publication {} after {} attempt(s)",
                publication.getIdentifier(), publication.getCompletionAttempts());
        return true;
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

package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.ResubmissionOptions;

class FailedPublicationResubmitterTests {

    private static final Instant LAST = Instant.parse("2026-11-23T10:00:00Z");
    private static final Duration INITIAL = Duration.ofMinutes(1);
    private static final Duration MAX = Duration.ofMinutes(30);

    @Test
    void firstFailureWaitsTheInitialBackoff() {
        assertFalse(FailedPublicationResubmitter.isDue(1, LAST, LAST.plusSeconds(59), INITIAL, MAX));
        assertTrue(FailedPublicationResubmitter.isDue(1, LAST, LAST.plusSeconds(60), INITIAL, MAX));
        assertTrue(FailedPublicationResubmitter.isDue(0, LAST, LAST.plusSeconds(60), INITIAL, MAX));
    }

    @Test
    void waitDoublesWithEveryFurtherAttempt() {
        assertFalse(FailedPublicationResubmitter.isDue(3, LAST, LAST.plus(Duration.ofMinutes(3)), INITIAL, MAX));
        assertTrue(FailedPublicationResubmitter.isDue(3, LAST, LAST.plus(Duration.ofMinutes(4)), INITIAL, MAX));
    }

    @Test
    void waitIsCappedAndNeverOverflows() {
        assertTrue(FailedPublicationResubmitter.isDue(10, LAST, LAST.plus(MAX), INITIAL, MAX));
        assertFalse(FailedPublicationResubmitter.isDue(10, LAST, LAST.plus(MAX).minusSeconds(1), INITIAL, MAX));
        assertTrue(FailedPublicationResubmitter.isDue(Integer.MAX_VALUE, LAST, LAST.plus(MAX), INITIAL, MAX));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new EventResubmissionSettings(Duration.ZERO, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new EventResubmissionSettings(null, Duration.ofMinutes(5), Duration.ofMinutes(1), null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new EventResubmissionSettings(null, null, null, 0, null));
    }

    @Test
    void frameworkReadsTheWholeBatchSoNotYetDueRowsCannotStarveDueOnes() {
        EventResubmissionSettings settings = new EventResubmissionSettings(null, INITIAL, MAX, 50, 4);
        ResubmissionOptions options = FailedPublicationResubmitter.options(settings, LAST);

        // The framework limits rows read to min(batchSize, maxInFlight - RESUBMITTED).
        assertEquals(50, options.getBatchSize());
        assertEquals(50, options.getMaxInFlight());

        Predicate<EventPublication> filter = options.getFilter();
        // The four oldest failures were retried a moment ago and are not due yet.
        for (int i = 0; i < 4; i++) {
            assertFalse(filter.test(new Failed(LAST.minus(Duration.ofHours(1)), LAST.minusSeconds(10), 3)));
        }
        assertTrue(filter.test(new Failed(LAST.minus(Duration.ofMinutes(5)), null, 1)),
            "a newer due publication behind not-yet-due ones is resubmitted");
    }

    @Test
    void atMostMaxInFlightDuePublicationsAreAcceptedPerRun() {
        EventResubmissionSettings settings = new EventResubmissionSettings(null, INITIAL, MAX, 50, 2);
        Predicate<EventPublication> filter = FailedPublicationResubmitter.options(settings, LAST).getFilter();
        Failed due = new Failed(LAST.minus(Duration.ofHours(1)), null, 1);

        List<Boolean> accepted = List.of(filter.test(due), filter.test(due), filter.test(due));
        assertEquals(List.of(true, true, false), accepted);
        // A new run starts with fresh headroom.
        assertTrue(FailedPublicationResubmitter.options(settings, LAST).getFilter().test(due));
    }

    /** Failed publication whose payload cannot be deserialized: the filter must never read it. */
    private record Failed(Instant published, Instant lastResubmission, int attempts) implements EventPublication {
        @Override public UUID getIdentifier() { return UUID.nameUUIDFromBytes(toString().getBytes()); }
        @Override public Object getEvent() { throw new IllegalStateException("incompatible payload"); }
        @Override public Instant getPublicationDate() { return published; }
        @Override public Optional<Instant> getCompletionDate() { return Optional.empty(); }
        @Override public Status getStatus() { return Status.FAILED; }
        @Override public Instant getLastResubmissionDate() { return lastResubmission; }
        @Override public int getCompletionAttempts() { return attempts; }
    }
}

package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

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
}

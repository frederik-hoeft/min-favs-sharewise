package de.hsh.favs.sharewise.testsupport.execution;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A deterministic clock that can be advanced across test threads. */
public final class AdjustableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public AdjustableClock(Instant initial) {
        this(new AtomicReference<>(Objects.requireNonNull(initial)), ZoneOffset.UTC);
    }

    private AdjustableClock(AtomicReference<Instant> instant, ZoneId zone) {
        this.instant = instant;
        this.zone = Objects.requireNonNull(zone);
    }

    public void setInstant(Instant value) {
        instant.set(Objects.requireNonNull(value));
    }

    @Override public Instant instant() { return instant.get(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new AdjustableClock(instant, zone); }
}

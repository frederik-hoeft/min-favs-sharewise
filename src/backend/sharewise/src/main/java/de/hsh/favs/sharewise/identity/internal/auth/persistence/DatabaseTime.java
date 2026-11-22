package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Converts between application instants and jOOQ's PostgreSQL timestamptz mapping. */
final class DatabaseTime {

    private DatabaseTime() { }

    static OffsetDateTime dbTime(Instant time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

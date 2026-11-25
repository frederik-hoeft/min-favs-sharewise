package de.hsh.favs.sharewise.finance.internal.group;

import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot of a Finance root at lock acquisition. */
public record FinancialGroup(UUID id, FinancialGroupState state, long currentRevision) {

    public FinancialGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        if (currentRevision < 0) {
            throw new IllegalArgumentException("currentRevision must be nonnegative");
        }
    }
}

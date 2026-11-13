package de.hsh.favs.sharewise.shared.infra.transactions;

/**
 * Persistence-adapter access to the current transaction segment's lifetime.
 * A lease identifies one physical segment; it never follows later segments of
 * the same operation-scoped {@link Transactions} manager.
 */
public interface TransactionSegmentAccess {

    /** Capture an active segment, or fail if there is none in the current operation. */
    SegmentLease captureCurrentSegment();

    /** A segment-specific, owner-thread and operation-scope validity check. */
    @FunctionalInterface
    interface SegmentLease {
        void requireCurrent();
    }
}

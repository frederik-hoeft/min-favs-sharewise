package de.hsh.favs.sharewise.groups.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Test-only {@link GroupEventPublisher} that records published facts. It rejects publication
 * outside a recorded transaction segment and adds a {@code publish:<fact>} entry to the shared
 * {@link InMemoryGroups} call log, so tests can verify that facts follow the last write.
 */
final class RecordingGroupEvents implements GroupEventPublisher {

    sealed interface Fact permits Created, Added, Removed {
    }

    record Created(UUID groupId) implements Fact {
    }

    record Added(UUID groupId, UUID userId, long membershipRevision) implements Fact {
    }

    record Removed(UUID groupId, UUID userId, long membershipRevision) implements Fact {
    }

    private final InMemoryGroups calls;
    private final RecordingTransactions.Log transactions;
    private final List<Fact> facts = new ArrayList<>();

    RecordingGroupEvents(InMemoryGroups calls, RecordingTransactions.Log transactions) {
        this.calls = Objects.requireNonNull(calls, "calls");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void groupCreated(UUID groupId) {
        record("groupCreated", new Created(groupId));
    }

    @Override
    public void memberAdded(UUID groupId, UUID userId, long membershipRevision) {
        record("memberAdded", new Added(groupId, userId, membershipRevision));
    }

    @Override
    public void memberRemoved(UUID groupId, UUID userId, long membershipRevision) {
        record("memberRemoved", new Removed(groupId, userId, membershipRevision));
    }

    synchronized List<Fact> facts() {
        return List.copyOf(facts);
    }

    synchronized void reset() {
        facts.clear();
    }

    private synchronized void record(String call, Fact fact) {
        if (!transactions.inSegment()) {
            throw new IllegalStateException("Group facts must be published inside a transaction segment");
        }
        facts.add(fact);
        calls.recordCall("publish:" + call);
    }
}

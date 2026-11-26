package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.FinancialActorStore;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupLookup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Test-only in-memory Finance stores and segment owner sharing one call journal. A segment keeps
 * its changes only if it commits; any other disposition or a callback failure restores the state
 * at segment start, like a physical rollback. As in {@code SpringTransactions}, requesting a
 * commit of a segment marked rollback-only fails with {@link IllegalStateException} and rolls
 * the segment back. The stores enforce the root-lock protocol: they
 * require an active segment, and actor access requires the group's root lock from the same
 * segment. PostgreSQL locking and constraint semantics are covered by the E2E and Integration
 * suites.
 */
final class RecordingFinanceStores {

    private final List<String> journal = new ArrayList<>();
    private final Map<UUID, FinancialGroup> roots = new HashMap<>();
    private final Map<ActorKey, FinancialActor> actors = new HashMap<>();
    private final Set<UUID> locked = new HashSet<>();
    private boolean active;
    private boolean rollbackOnly;
    private String failingCall;
    private RuntimeException failure;

    private final Transactions transactions = new Transactions() {
        @Override
        public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
            Objects.requireNonNull(operation, "operation");
            if (active) {
                throw new IllegalStateException("nested segment");
            }
            Map<UUID, FinancialGroup> rootsAtStart = Map.copyOf(roots);
            Map<ActorKey, FinancialActor> actorsAtStart = Map.copyOf(actors);
            active = true;
            rollbackOnly = false;
            journal.add("begin");
            boolean committed = false;
            try {
                TransactionOutcome<T> outcome = Objects.requireNonNull(operation.get(), "outcome");
                if (rollbackOnly && outcome.decision() == TransactionDecision.COMMIT) {
                    // Mirrors SpringTransactions: the segment fails and rolls back instead of downgrading.
                    throw new IllegalStateException("Cannot commit a rollback-only transaction segment");
                }
                journal.add(outcome.decision().name());
                committed = outcome.decision() == TransactionDecision.COMMIT;
                return outcome.result();
            } catch (RuntimeException | Error callbackFailure) {
                journal.add("failed");
                throw callbackFailure;
            } finally {
                if (!committed) {
                    roots.clear();
                    roots.putAll(rootsAtStart);
                    actors.clear();
                    actors.putAll(actorsAtStart);
                }
                locked.clear();
                active = false;
            }
        }

        @Override
        public void markRollbackOnly() {
            requireActive();
            rollbackOnly = true;
        }

        @Override
        public boolean isRollbackOnly() {
            requireActive();
            return rollbackOnly;
        }
    };

    private final FinancialGroupStore groups = new FinancialGroupStore() {
        @Override
        public FinancialGroup ensureAndLock(UUID groupId) {
            call("groups.ensureAndLock");
            locked.add(groupId);
            return roots.computeIfAbsent(groupId,
                    id -> new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0));
        }

        @Override
        public FinancialGroupLookup lockExisting(UUID groupId) {
            call("groups.lockExisting");
            Optional<FinancialGroup> root = Optional.ofNullable(roots.get(groupId));
            root.ifPresent(present -> locked.add(present.id()));
            return new FinancialGroupLookup(root);
        }

        @Override
        public FinancialGroup activate(FinancialGroup snapshot) {
            call("groups.activate");
            requireLocked(snapshot.id());
            if (snapshot.state() != FinancialGroupState.PROVISIONING
                    || roots.get(snapshot.id()).state() != FinancialGroupState.PROVISIONING) {
                throw new IllegalStateException("not provisioning");
            }
            FinancialGroup open = new FinancialGroup(snapshot.id(), FinancialGroupState.OPEN, snapshot.currentRevision());
            roots.put(open.id(), open);
            return open;
        }
    };

    private final FinancialActorStore actorStore = new FinancialActorStore() {
        @Override
        public Optional<FinancialActor> find(UUID groupId, UUID userId) {
            call("actors.find");
            requireLocked(groupId);
            return Optional.ofNullable(actors.get(new ActorKey(groupId, userId)));
        }

        @Override
        public void insert(FinancialActor actor) {
            call("actors.insert");
            requireLocked(actor.groupId());
            if (!roots.containsKey(actor.groupId())) {
                throw new IllegalStateException("missing root");
            }
            if (actors.putIfAbsent(ActorKey.of(actor), actor) != null) {
                throw new IllegalStateException("duplicate actor");
            }
        }

        @Override
        public void supersede(FinancialActor current, FinancialActor next) {
            call("actors.supersede");
            requireLocked(current.groupId());
            if (!current.sameIdentity(next) || next.upstreamRevision() <= current.upstreamRevision()) {
                throw new IllegalArgumentException("invalid supersede");
            }
            if (!actors.replace(ActorKey.of(current), current, next)) {
                throw new IllegalStateException("stored actor differs");
            }
        }
    };

    Transactions transactions() {
        return transactions;
    }

    FinancialGroupStore groups() {
        return groups;
    }

    FinancialActorStore actors() {
        return actorStore;
    }

    /** Calls and segment dispositions in order since construction or the last reset. */
    List<String> journal() {
        return List.copyOf(journal);
    }

    void resetJournal() {
        journal.clear();
    }

    /** Fails the next invocation of the named store call after recording it, then disarms. */
    void failOn(String call, RuntimeException toThrow) {
        failingCall = Objects.requireNonNull(call, "call");
        failure = Objects.requireNonNull(toThrow, "toThrow");
    }

    void seedRoot(FinancialGroup root) {
        roots.put(root.id(), root);
    }

    void seedActor(FinancialActor actor) {
        if (!roots.containsKey(actor.groupId())) {
            throw new IllegalStateException("missing root");
        }
        actors.put(ActorKey.of(actor), actor);
    }

    Optional<FinancialGroup> root(UUID groupId) {
        return Optional.ofNullable(roots.get(groupId));
    }

    Optional<FinancialActor> actor(UUID groupId, UUID userId) {
        return Optional.ofNullable(actors.get(new ActorKey(groupId, userId)));
    }

    int actorCount() {
        return actors.size();
    }

    private void call(String name) {
        requireActive();
        journal.add(name);
        if (name.equals(failingCall)) {
            RuntimeException thrown = failure;
            failingCall = null;
            failure = null;
            throw thrown;
        }
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("No active segment");
        }
    }

    private void requireLocked(UUID groupId) {
        if (!locked.contains(groupId)) {
            throw new IllegalStateException("Root lock not held for " + groupId);
        }
    }

    private record ActorKey(UUID groupId, UUID userId) {
        static ActorKey of(FinancialActor actor) {
            return new ActorKey(actor.groupId(), actor.userId());
        }
    }
}

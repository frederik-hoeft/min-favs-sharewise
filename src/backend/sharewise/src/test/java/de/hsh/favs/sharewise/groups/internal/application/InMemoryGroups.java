package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only, in-memory implementation of the Group ports for application-service tests.
 * It records port calls so tests can verify the order of the group mutation protocol.
 * Database semantics (atomicity, locking, constraints) are verified against PostgreSQL instead.
 */
final class InMemoryGroups implements GroupUserRegistry, GroupCreationPort, GroupReadPort, GroupMutationPort {

    private static final Instant CREATED_AT = Instant.parse("2026-11-21T12:00:00Z");

    private final Set<UUID> users = new HashSet<>();
    private final Map<UUID, StoredGroup> groups = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private int writes;

    synchronized void reset() {
        users.clear();
        groups.clear();
        calls.clear();
        writes = 0;
    }

    /** Seeds a group with explicit memberships, bypassing the services. */
    synchronized UUID seedGroup(GroupMetadata metadata, long version, Map<UUID, GroupRole> members) {
        UUID id = UUID.randomUUID();
        groups.put(id, new StoredGroup(id, metadata, version, new LinkedHashMap<>(members)));
        return id;
    }

    synchronized Optional<StoredGroup> stored(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId)).map(StoredGroup::copy);
    }

    synchronized void clearCalls() {
        calls.clear();
    }

    synchronized int writes() {
        return writes;
    }

    synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public synchronized boolean isKnown(UUID userId) {
        calls.add("isKnown");
        return users.contains(userId);
    }

    @Override
    public synchronized void provision(UUID userId) {
        calls.add("provision");
        users.add(userId);
    }

    @Override
    public synchronized GroupDetails createWithAdministrator(UUID creatorId, GroupMetadata metadata) {
        calls.add("create");
        if (!users.contains(creatorId)) {
            throw new IllegalStateException("simulated foreign-key violation for unknown creator");
        }
        writes++;
        UUID id = seedGroup(metadata, 0, Map.of(creatorId, GroupRole.ADMIN));
        return details(groups.get(id), creatorId);
    }

    @Override
    public synchronized List<GroupSummary> listForMember(UUID userId) {
        calls.add("list");
        return groups.values().stream()
            .filter(group -> group.members().containsKey(userId))
            .sorted(Comparator.comparing((StoredGroup group) -> group.metadata().name().value().toLowerCase())
                .thenComparing(StoredGroup::id))
            .map(group -> new GroupSummary(group.id(), group.metadata(), group.members().size(),
                group.members().get(userId)))
            .toList();
    }

    @Override
    public synchronized Optional<GroupDetails> findForMember(UUID groupId, UUID userId) {
        calls.add("find");
        return Optional.ofNullable(groups.get(groupId))
            .filter(group -> group.members().containsKey(userId))
            .map(group -> details(group, userId));
    }

    @Override
    public synchronized Optional<LockedGroup> lockGroup(UUID groupId) {
        calls.add("lock");
        return Optional.ofNullable(groups.get(groupId)).map(group -> new LockedGroup(group.id(), group.version()));
    }

    @Override
    public synchronized Optional<GroupRole> memberRole(LockedGroup group, UUID userId) {
        calls.add("role");
        return Optional.ofNullable(groups.get(group.id()).members().get(userId));
    }

    @Override
    public synchronized GroupDetails replaceMetadata(LockedGroup group, UUID callerId, GroupMetadata metadata) {
        calls.add("replace");
        writes++;
        StoredGroup current = groups.get(group.id());
        StoredGroup updated = new StoredGroup(current.id(), metadata, current.version() + 1, current.members());
        groups.put(updated.id(), updated);
        return details(updated, callerId);
    }

    private static GroupDetails details(StoredGroup group, UUID callerId) {
        return new GroupDetails(group.id(), group.metadata(), CREATED_AT, group.version(), group.members().size(),
            group.members().get(callerId));
    }

    record StoredGroup(UUID id, GroupMetadata metadata, long version, Map<UUID, GroupRole> members) {
        StoredGroup copy() {
            return new StoredGroup(id, metadata, version, Map.copyOf(members));
        }
    }
}

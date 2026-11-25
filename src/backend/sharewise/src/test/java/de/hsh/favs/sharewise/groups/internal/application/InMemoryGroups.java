package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.application.access.GroupLockPort;
import de.hsh.favs.sharewise.groups.internal.application.access.GroupUserRegistry;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMutationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupReadPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipPort;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeContext;
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
final class InMemoryGroups implements GroupUserRegistry, GroupCreationPort, GroupReadPort, GroupLockPort, GroupMutationPort, GroupMembershipPort {

    private static final Instant CREATED_AT = Instant.parse("2026-11-21T12:00:00Z");

    private final Set<UUID> users = new HashSet<>();
    private final Map<UUID, StoredGroup> groups = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, UUID>> membershipIds = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private int writes;

    synchronized void reset() {
        users.clear();
        groups.clear();
        membershipIds.clear();
        calls.clear();
        writes = 0;
    }

    /** Seeds a group with explicit memberships, bypassing the services. */
    synchronized UUID seedGroup(GroupMetadata metadata, long version, Map<UUID, GroupRole> members) {
        if (!users.containsAll(members.keySet())) {
            throw new IllegalStateException("Unknown seeded member");
        }
        UUID id = UUID.randomUUID();
        groups.put(id, new StoredGroup(id, metadata, version, new LinkedHashMap<>(members)));
        Map<UUID, UUID> ids = new LinkedHashMap<>();
        members.keySet().forEach(user -> ids.put(user, UUID.randomUUID()));
        membershipIds.put(id, ids);
        return id;
    }

    synchronized Optional<StoredGroup> stored(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId)).map(StoredGroup::copy);
    }

    synchronized void clearCalls() {
        calls.clear();
    }

    synchronized void recordCall(String call) {
        calls.add(call);
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
    public synchronized Optional<List<GroupSummary>> listForMember(UUID userId) {
        calls.add("list");
        if (!users.contains(userId)) {
            return Optional.empty();
        }
        return Optional.of(groups.values().stream()
            .filter(group -> group.members().containsKey(userId))
            .sorted(Comparator.comparing((StoredGroup group) -> group.metadata().name().value().toLowerCase())
                .thenComparing(StoredGroup::id))
            .map(group -> new GroupSummary(group.id(), group.metadata(), group.members().size(),
                group.members().get(userId)))
            .toList());
    }

    @Override
    public synchronized Optional<GroupDetails> findForMember(UUID groupId, UUID userId) {
        calls.add("find");
        return Optional.ofNullable(groups.get(groupId))
            .filter(group -> group.members().containsKey(userId))
            .map(group -> details(group, userId));
    }

    @Override
    public synchronized Optional<LockedGroup> lockGroup(UUID groupId, UUID userId) {
        calls.add("lock");
        if (!users.contains(userId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(groups.get(groupId)).map(group -> new LockedGroup(group.id(), group.version()));
    }

    @Override
    public synchronized Optional<GroupWorkspace> findWorkspace(UUID groupId, UUID userId) {
        calls.add("workspace");
        StoredGroup group = groups.get(groupId);
        if (group == null || !group.members().containsKey(userId)) {
            return Optional.empty();
        }
        List<GroupMember> roster = group.members().keySet().stream().map(user -> member(group, user))
            .sorted(Comparator.comparing(GroupMember::joinedAt).thenComparing(GroupMember::id)).toList();
        return Optional.of(new GroupWorkspace(details(group, userId), membershipIds.get(groupId).get(userId), roster));
    }

    @Override
    public synchronized Optional<GroupMember> memberForUser(LockedGroup group, UUID userId) {
        calls.add("role");
        StoredGroup current = groups.get(group.id());
        return current.members().containsKey(userId) ? Optional.of(member(current, userId)) : Optional.empty();
    }

    private synchronized Optional<GroupMember> memberById(LockedGroup group, UUID membershipId) {
        StoredGroup current = groups.get(group.id());
        return current.members().keySet().stream().map(user -> member(current, user))
            .filter(member -> member.id().equals(membershipId)).findFirst();
    }

    @Override
    public synchronized Optional<MembershipChangeContext> changeContext(LockedGroup group, UUID userId, UUID membershipId) {
        calls.add("changeContext");
        StoredGroup current = groups.get(group.id());
        if (!current.members().containsKey(userId)) {
            return Optional.empty();
        }
        GroupMember caller = member(current, userId);
        Optional<GroupMember> target = caller.role().isAdministrator() || caller.id().equals(membershipId)
            ? memberById(group, membershipId) : Optional.empty();
        return Optional.of(new MembershipChangeContext(caller, target,
            target.isPresent() && current.members().size() > 1,
            target.isPresent() && current.members().entrySet().stream().anyMatch(entry ->
                !entry.getKey().equals(target.get().userId()) && entry.getValue().isAdministrator())));
    }

    @Override
    public synchronized void setRole(LockedGroup group, UUID membershipId, GroupRole role) {
        GroupMember target = memberById(group, membershipId).orElseThrow();
        groups.get(group.id()).members().put(target.userId(), role);
        calls.add("setRole");
        writes++;
    }

    @Override
    public synchronized void removeMember(LockedGroup group, UUID membershipId) {
        GroupMember target = memberById(group, membershipId).orElseThrow();
        groups.get(group.id()).members().remove(target.userId());
        membershipIds.get(group.id()).remove(target.userId());
        calls.add("remove");
        writes++;
    }

    @Override
    public synchronized void addMember(LockedGroup group, UUID userId) {
        if (groups.get(group.id()).members().containsKey(userId)) {
            throw new IllegalStateException("Membership already exists");
        }
        addMembership(group.id(), userId, GroupRole.MEMBER);
        calls.add("addMember");
        writes++;
    }

    synchronized void addMembership(UUID groupId, UUID userId, GroupRole role) {
        if (!users.contains(userId)) {
            throw new IllegalStateException("Unknown member");
        }
        groups.get(groupId).members().put(userId, role);
        membershipIds.get(groupId).put(userId, UUID.randomUUID());
    }

    private GroupMember member(StoredGroup group, UUID userId) {
        return new GroupMember(membershipIds.get(group.id()).get(userId), userId,
            group.members().get(userId), CREATED_AT);
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

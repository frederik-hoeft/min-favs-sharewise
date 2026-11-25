package de.hsh.favs.sharewise.groups.internal.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Application-policy fixture, not a simulation of database transaction semantics. */
final class InMemoryInvitations implements GroupInvitationPort {
    private final InMemoryGroups groups;

    InMemoryInvitations(InMemoryGroups groups) {
        this.groups = groups;
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    Instant now = Instant.parse("2026-11-25T12:00:00Z");

    void reset() {
        entries.clear();
        now = Instant.parse("2026-11-25T12:00:00Z");
    }

    @Override public InvitationIssuanceMaterial issuanceMaterial(LockedGroup group) {
        groups.recordCall("issuanceMaterial");
        return new InvitationIssuanceMaterial(UUID.randomUUID(), now);
    }

    @Override public Optional<LockedGroup> lockGroupForRedemption(UUID selector, UUID userId) {
        return Optional.ofNullable(entries.get(selector)).flatMap(entry -> groups.lockGroup(entry.groupId(), userId));
    }

    @Override public Optional<InvitationListing> listForMember(UUID groupId, UUID userId) {
        groups.recordCall("invitationList");
        return groups.stored(groupId).map(group -> group.members().get(userId))
            .map(role -> new InvitationListing(role, role.isAdministrator() ? entries.values().stream()
                .filter(entry -> entry.groupId().equals(groupId)).map(Entry::summary).toList() : List.of()));
    }

    @Override public Optional<InvitationRedemptionContext> redemptionContext(LockedGroup group, UUID selector, UUID userId) {
        groups.recordCall("redemptionContext");
        return find(group, selector).map(invitation -> new InvitationRedemptionContext(invitation, now,
            groups.stored(group.id()).orElseThrow().members().containsKey(userId)));
    }

    private Optional<StoredInvitation> find(LockedGroup group, UUID selector) {
        return Optional.ofNullable(entries.get(selector)).filter(entry -> entry.groupId().equals(group.id()))
            .map(entry -> new StoredInvitation(entry.summary(), entry.verifier()));
    }

    @Override public InvitationSummary insert(LockedGroup group, UUID selector, byte[] verifier,
            Instant createdAt, Instant expiresAt, Integer maxUses) {
        groups.recordCall("insertInvitation");
        InvitationSummary summary = new InvitationSummary(selector, createdAt, expiresAt, maxUses, 0);
        entries.put(selector, new Entry(group.id(), summary, verifier.clone()));
        return summary;
    }

    @Override public boolean revoke(LockedGroup group, UUID selector) {
        groups.recordCall("revokeInvitation");
        return find(group, selector).isPresent() && entries.remove(selector) != null;
    }

    @Override public void consume(LockedGroup group, UUID selector) {
        groups.recordCall("consumeInvitation");
        Entry entry = entries.get(selector);
        InvitationSummary old = entry.summary();
        if (!entry.groupId().equals(group.id())) throw new IllegalStateException("Wrong group");
        entries.put(selector, new Entry(group.id(), new InvitationSummary(selector, old.createdAt(),
            old.expiresAt(), old.maxUses(), Math.addExact(old.useCount(), 1)), entry.verifier()));
    }

    private record Entry(UUID groupId, InvitationSummary summary, byte[] verifier) { }
}

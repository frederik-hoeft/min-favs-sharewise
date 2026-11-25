package de.hsh.favs.sharewise.groups.internal.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Application-policy fixture, not a simulation of database transaction semantics. */
final class InMemoryInvitations implements GroupInvitationPort {
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    Instant now = Instant.parse("2026-11-25T12:00:00Z");

    void reset() {
        entries.clear();
        now = Instant.parse("2026-11-25T12:00:00Z");
    }

    @Override public Instant currentTime() { return now; }
    @Override public UUID newInvitationId() { return UUID.randomUUID(); }

    @Override public Optional<UUID> groupIdForSelector(UUID selector) {
        return Optional.ofNullable(entries.get(selector)).map(Entry::groupId);
    }

    @Override public List<InvitationSummary> list(LockedGroup group) {
        return entries.values().stream().filter(entry -> entry.groupId().equals(group.id()))
            .map(Entry::summary).toList();
    }

    @Override public Optional<StoredInvitation> find(LockedGroup group, UUID selector) {
        return Optional.ofNullable(entries.get(selector)).filter(entry -> entry.groupId().equals(group.id()))
            .map(entry -> new StoredInvitation(entry.summary(), entry.verifier()));
    }

    @Override public InvitationSummary insert(LockedGroup group, UUID selector, byte[] verifier,
            Instant createdAt, Instant expiresAt, Integer maxUses) {
        InvitationSummary summary = new InvitationSummary(selector, createdAt, expiresAt, maxUses, 0);
        entries.put(selector, new Entry(group.id(), summary, verifier.clone()));
        return summary;
    }

    @Override public boolean revoke(LockedGroup group, UUID selector) {
        return find(group, selector).isPresent() && entries.remove(selector) != null;
    }

    @Override public void consume(LockedGroup group, UUID selector) {
        Entry entry = entries.get(selector);
        InvitationSummary old = entry.summary();
        if (!entry.groupId().equals(group.id())) throw new IllegalStateException("Wrong group");
        entries.put(selector, new Entry(group.id(), new InvitationSummary(selector, old.createdAt(),
            old.expiresAt(), old.maxUses(), Math.addExact(old.useCount(), 1)), entry.verifier()));
    }

    private record Entry(UUID groupId, InvitationSummary summary, byte[] verifier) { }
}

package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.INVITATIONS;

import de.hsh.favs.sharewise.groups.internal.application.GroupInvitationPort;
import de.hsh.favs.sharewise.groups.internal.application.InvitationSummary;
import de.hsh.favs.sharewise.groups.internal.application.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.StoredInvitation;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

@Repository
@Scope("operation")
class JooqGroupInvitations implements GroupInvitationPort {
    private final JooqAccess database;

    JooqGroupInvitations(JooqAccess database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override
    public Optional<UUID> groupIdForSelector(UUID selector) {
        return database.dsl().select(INVITATIONS.GROUP_ID).from(INVITATIONS)
            .where(INVITATIONS.ID.eq(selector)).fetchOptional(row -> row.value1());
    }

    @Override
    public Instant currentTime() {
        return database.dsl().select(DSL.function("clock_timestamp", OffsetDateTime.class))
            .fetchSingle().value1().toInstant();
    }

    @Override
    public UUID newInvitationId() {
        return database.dsl().select(DSL.function("uuidv7", UUID.class)).fetchSingle().value1();
    }

    @Override
    public List<InvitationSummary> list(LockedGroup group) {
        return database.dsl().select(INVITATIONS.ID, INVITATIONS.CREATED_AT, INVITATIONS.EXPIRES_AT,
                INVITATIONS.MAX_USES, INVITATIONS.USE_COUNT).from(INVITATIONS)
            .where(INVITATIONS.GROUP_ID.eq(group.id())).orderBy(INVITATIONS.CREATED_AT.desc(), INVITATIONS.ID)
            .fetch(JooqGroupInvitations::summary);
    }

    @Override
    public Optional<StoredInvitation> find(LockedGroup group, UUID selector) {
        return database.dsl().selectFrom(INVITATIONS)
            .where(INVITATIONS.GROUP_ID.eq(group.id()).and(INVITATIONS.ID.eq(selector)))
            .fetchOptional(row -> new StoredInvitation(summary(row), row.getVerifierHash()));
    }

    @Override
    public InvitationSummary insert(LockedGroup group, UUID selector, byte[] verifier, Instant createdAt,
            Instant expiresAt, Integer maxUses) {
        return database.dsl().insertInto(INVITATIONS)
            .set(INVITATIONS.ID, selector).set(INVITATIONS.GROUP_ID, group.id())
            .set(INVITATIONS.VERIFIER_HASH, verifier).set(INVITATIONS.CREATED_AT, atUtc(createdAt))
            .set(INVITATIONS.EXPIRES_AT, atUtc(expiresAt)).set(INVITATIONS.MAX_USES, maxUses)
            .returningResult(INVITATIONS.ID, INVITATIONS.CREATED_AT, INVITATIONS.EXPIRES_AT,
                INVITATIONS.MAX_USES, INVITATIONS.USE_COUNT).fetchSingle(JooqGroupInvitations::summary);
    }

    @Override
    public boolean revoke(LockedGroup group, UUID selector) {
        return database.dsl().deleteFrom(INVITATIONS)
            .where(INVITATIONS.GROUP_ID.eq(group.id()).and(INVITATIONS.ID.eq(selector))).execute() == 1;
    }

    @Override
    public void consume(LockedGroup group, UUID selector) {
        int changed = database.dsl().update(INVITATIONS)
            .set(INVITATIONS.USE_COUNT, INVITATIONS.USE_COUNT.plus(1))
            .where(INVITATIONS.GROUP_ID.eq(group.id()).and(INVITATIONS.ID.eq(selector)))
            .execute();
        if (changed != 1) {
            throw new IllegalStateException("Expected one current invitation under the group root lock");
        }
    }

    private static InvitationSummary summary(Record row) {
        OffsetDateTime expiry = row.get(INVITATIONS.EXPIRES_AT);
        return new InvitationSummary(row.get(INVITATIONS.ID), row.get(INVITATIONS.CREATED_AT).toInstant(),
            expiry == null ? null : expiry.toInstant(), row.get(INVITATIONS.MAX_USES), row.get(INVITATIONS.USE_COUNT));
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUPS_;
import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;
import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.INVITATIONS;

import de.hsh.favs.sharewise.groups.internal.application.GroupInvitationPort;
import de.hsh.favs.sharewise.groups.internal.application.InvitationIssuanceMaterial;
import de.hsh.favs.sharewise.groups.internal.application.InvitationListing;
import de.hsh.favs.sharewise.groups.internal.application.InvitationPreviewContext;
import de.hsh.favs.sharewise.groups.internal.application.InvitationRedemptionContext;
import de.hsh.favs.sharewise.groups.internal.application.InvitationSummary;
import de.hsh.favs.sharewise.groups.internal.application.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.StoredInvitation;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Record6;
import org.jooq.Record;
import org.jooq.Result;
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
    public Optional<InvitationListing> listForMember(UUID groupId, UUID userId) {
        Field<GroupRole> role = GroupProjections.role(GROUP_MEMBERS);
        Result<Record6<GroupRole, UUID, OffsetDateTime, OffsetDateTime, Integer, Integer>> rows = database.dsl()
            .select(role, INVITATIONS.ID, INVITATIONS.CREATED_AT, INVITATIONS.EXPIRES_AT,
                INVITATIONS.MAX_USES, INVITATIONS.USE_COUNT)
            .from(GROUP_MEMBERS).leftJoin(INVITATIONS).on(INVITATIONS.GROUP_ID.eq(GROUP_MEMBERS.GROUP_ID)
                .and(GROUP_MEMBERS.ROLE.eq(GroupProjections.toDatabase(GroupRole.ADMIN))))
            .where(GROUP_MEMBERS.GROUP_ID.eq(groupId).and(GROUP_MEMBERS.USER_ID.eq(userId)))
            .orderBy(INVITATIONS.CREATED_AT.desc(), INVITATIONS.ID).fetch();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // Non-administrators and administrators with no invitations have one null invitation row.
        List<InvitationSummary> summaries = rows.getFirst().get(INVITATIONS.ID) == null
            ? List.of() : rows.map(JooqGroupInvitations::summary);
        return Optional.of(new InvitationListing(rows.getFirst().value1(), summaries));
    }

    @Override
    public Optional<InvitationPreviewContext> previewContext(UUID selector, UUID userId) {
        Field<OffsetDateTime> now = wallClock();
        return database.dsl().select(INVITATIONS.ID, INVITATIONS.CREATED_AT, INVITATIONS.EXPIRES_AT,
                INVITATIONS.MAX_USES, INVITATIONS.USE_COUNT, INVITATIONS.VERIFIER_HASH, now, GROUPS_.NAME)
            .from(INVITATIONS).join(GROUPS_).on(GROUPS_.ID.eq(INVITATIONS.GROUP_ID))
            .where(INVITATIONS.ID.eq(selector).and(GroupProjections.knownUser(userId)))
            .fetchOptional(row -> new InvitationPreviewContext(
                new StoredInvitation(summary(row), row.get(INVITATIONS.VERIFIER_HASH)),
                row.get(now).toInstant(), row.get(GROUPS_.NAME)));
    }

    @Override
    public Optional<LockedGroup> lockGroupForRedemption(UUID selector, UUID userId) {
        return database.dsl().select(GROUPS_.ID, GROUPS_.VERSION).from(GROUPS_)
            .join(INVITATIONS).on(INVITATIONS.GROUP_ID.eq(GROUPS_.ID))
            .where(INVITATIONS.ID.eq(selector).and(GroupProjections.knownUser(userId)))
            .forUpdate().of(GROUPS_)
            .fetchOptional(row -> new LockedGroup(row.value1(), row.value2()));
    }

    @Override
    public Optional<InvitationRedemptionContext> redemptionContext(LockedGroup group, UUID selector, UUID userId) {
        Field<OffsetDateTime> now = wallClock();
        Field<Boolean> alreadyMember = DSL.field(DSL.exists(DSL.selectOne().from(GROUP_MEMBERS)
            .where(GROUP_MEMBERS.GROUP_ID.eq(group.id()).and(GROUP_MEMBERS.USER_ID.eq(userId)))));
        return database.dsl().select(INVITATIONS.ID, INVITATIONS.CREATED_AT, INVITATIONS.EXPIRES_AT,
                INVITATIONS.MAX_USES, INVITATIONS.USE_COUNT, INVITATIONS.VERIFIER_HASH, now, alreadyMember)
            .from(INVITATIONS).where(INVITATIONS.GROUP_ID.eq(group.id()).and(INVITATIONS.ID.eq(selector)))
            .fetchOptional(row -> new InvitationRedemptionContext(
                new StoredInvitation(summary(row), row.get(INVITATIONS.VERIFIER_HASH)),
                row.get(now).toInstant(), row.get(alreadyMember)));
    }

    @Override
    public InvitationIssuanceMaterial issuanceMaterial(LockedGroup group) {
        return database.dsl().select(DSL.function("uuidv7", UUID.class), wallClock())
            .fetchSingle(row -> new InvitationIssuanceMaterial(row.value1(), row.value2().toInstant()));
    }

    private static Field<OffsetDateTime> wallClock() {
        return DSL.function("clock_timestamp", OffsetDateTime.class);
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

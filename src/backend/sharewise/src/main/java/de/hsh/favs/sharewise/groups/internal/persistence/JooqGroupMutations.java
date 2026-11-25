package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUPS_;

import de.hsh.favs.sharewise.groups.internal.application.access.GroupLockPort;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMutationPort;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Field;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * Group mutation protocol on PostgreSQL: {@code SELECT ... FOR UPDATE} on the group row first.
 * Under READ COMMITTED, every later statement in the segment then sees the latest committed
 * membership, and concurrent mutations of the same group serialize on that row lock.
 */
@Repository
@Scope("operation")
class JooqGroupMutations implements GroupLockPort, GroupMutationPort {

    private final JooqAccess database;

    JooqGroupMutations(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<LockedGroup> lockGroupForMember(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        return database.dsl()
                .select(GROUPS_.ID, GROUPS_.VERSION)
                .from(GROUPS_)
                .where(GROUPS_.ID.eq(groupId).and(GroupProjections.isMember(userId)))
                .forUpdate()
                .fetchOptional(row -> new LockedGroup(row.get(GROUPS_.ID), row.get(GROUPS_.VERSION)));
    }

    /**
     * The version predicate is a backstop: while the row lock is held, the version read under it
     * cannot change, so a missing row means the caller violated the protocol.
     */
    @Override
    public GroupDetails replaceMetadata(LockedGroup group, UUID callerId, GroupMetadata metadata) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(metadata, "metadata");
        Field<GroupRole> callerRole = GroupProjections.callerRole(callerId);
        return database.dsl()
                .update(GROUPS_)
                .set(GROUPS_.NAME, metadata.name().value())
                .set(GROUPS_.DESCRIPTION, metadata.description().map(GroupDescription::value).orElse(null))
                .set(GROUPS_.VERSION, GROUPS_.VERSION.plus(1L))
                .where(GROUPS_.ID.eq(group.id()).and(GROUPS_.VERSION.eq(group.version())))
                .returningResult(GROUPS_.ID, GROUPS_.NAME, GROUPS_.DESCRIPTION, GROUPS_.CREATED_AT, GROUPS_.VERSION,
                        GroupProjections.MEMBER_COUNT, callerRole)
                .fetchOptional(row -> GroupProjections.details(row, callerRole))
                .orElseThrow(() -> new IllegalStateException(
                        "Group " + group.id() + " changed although its row lock is held by this segment"));
    }
}

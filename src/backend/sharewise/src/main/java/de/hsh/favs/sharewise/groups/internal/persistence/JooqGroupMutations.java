package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUPS_;
import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.GroupMutationPort;
import de.hsh.favs.sharewise.groups.internal.application.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * Group mutation protocol on PostgreSQL: {@code SELECT ... FOR UPDATE} on the group row first.
 * Under READ COMMITTED, every later statement in the segment then sees the latest committed
 * membership, and concurrent mutations of the same group serialize on that row lock.
 */
@Repository
@Scope("operation")
class JooqGroupMutations implements GroupMutationPort {

    private final JooqAccess database;

    JooqGroupMutations(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<LockedGroup> lockGroup(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return database.dsl()
                .select(GROUPS_.ID, GROUPS_.VERSION)
                .from(GROUPS_)
                .where(GROUPS_.ID.eq(groupId))
                .forUpdate()
                .fetchOptional(row -> new LockedGroup(row.get(GROUPS_.ID), row.get(GROUPS_.VERSION)));
    }

    @Override
    public Optional<GroupMember> memberForUser(LockedGroup group, UUID userId) {
        return findMember(group, GROUP_MEMBERS.USER_ID.eq(userId));
    }

    @Override
    public Optional<GroupMember> memberById(LockedGroup group, UUID membershipId) {
        return findMember(group, GROUP_MEMBERS.ID.eq(membershipId));
    }

    private Optional<GroupMember> findMember(LockedGroup group, Condition predicate) {
        return database.dsl().select(GROUP_MEMBERS.ID, GROUP_MEMBERS.USER_ID, GROUP_MEMBERS.ROLE,
                GROUP_MEMBERS.JOINED_AT).from(GROUP_MEMBERS)
            .where(GROUP_MEMBERS.GROUP_ID.eq(group.id()).and(predicate))
            .fetchOptional(row -> GroupProjections.member(row, GROUP_MEMBERS));
    }

    @Override
    public int countMembers(LockedGroup group, Optional<GroupRole> role) {
        Condition predicate = GROUP_MEMBERS.GROUP_ID.eq(group.id()).and(role
            .map(value -> GROUP_MEMBERS.ROLE.eq(GroupProjections.toDatabase(value))).orElseGet(DSL::noCondition));
        return database.dsl().fetchCount(GROUP_MEMBERS, predicate);
    }

    @Override
    public void setRole(LockedGroup group, UUID membershipId, GroupRole role) {
        requireChanged(database.dsl().update(GROUP_MEMBERS).set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(role))
            .where(GROUP_MEMBERS.GROUP_ID.eq(group.id()).and(GROUP_MEMBERS.ID.eq(membershipId))).execute());
    }

    @Override
    public void removeMember(LockedGroup group, UUID membershipId) {
        requireChanged(database.dsl().deleteFrom(GROUP_MEMBERS)
            .where(GROUP_MEMBERS.GROUP_ID.eq(group.id()).and(GROUP_MEMBERS.ID.eq(membershipId))).execute());
    }

    private static void requireChanged(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Expected one current membership under the group root lock");
        }
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

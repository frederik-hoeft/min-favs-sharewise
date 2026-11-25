package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.GroupMembershipPort;
import de.hsh.favs.sharewise.groups.internal.application.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;


/** Membership persistence under the group-root mutation protocol. */
@Repository
@Scope("operation")
class JooqGroupMemberships implements GroupMembershipPort {
    private final JooqAccess database;

    JooqGroupMemberships(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
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
    public void addMember(LockedGroup group, UUID userId) {
        requireChanged(database.dsl().insertInto(GROUP_MEMBERS)
            .set(GROUP_MEMBERS.GROUP_ID, group.id())
            .set(GROUP_MEMBERS.USER_ID, userId)
            .set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(GroupRole.MEMBER)).execute());
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

}

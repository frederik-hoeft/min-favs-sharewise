package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.GroupMembershipPort;
import de.hsh.favs.sharewise.groups.internal.application.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.MembershipChangeContext;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.groups.internal.persistence.generated.tables.GroupMembers;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
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
        return database.dsl().select(GROUP_MEMBERS.ID, GROUP_MEMBERS.USER_ID, GROUP_MEMBERS.ROLE,
                GROUP_MEMBERS.JOINED_AT).from(GROUP_MEMBERS)
            .where(GROUP_MEMBERS.GROUP_ID.eq(group.id()).and(GROUP_MEMBERS.USER_ID.eq(userId)))
            .fetchOptional(row -> GroupProjections.member(row, GROUP_MEMBERS));
    }

    @Override
    public Optional<MembershipChangeContext> changeContext(LockedGroup group, UUID userId, UUID membershipId) {
        GroupMembers caller = GROUP_MEMBERS.as("caller");
        GroupMembers target = GROUP_MEMBERS.as("target");
        GroupMembers other = GROUP_MEMBERS.as("other_member");
        Condition otherInGroup = other.GROUP_ID.eq(group.id()).and(other.ID.ne(target.ID));
        Field<Boolean> otherMembers = DSL.field(DSL.exists(DSL.selectOne().from(other).where(otherInGroup)));
        Field<Boolean> otherAdmins = DSL.field(DSL.exists(DSL.selectOne().from(other)
            .where(otherInGroup.and(other.ROLE.eq(GroupProjections.toDatabase(GroupRole.ADMIN))))));
        return database.dsl().select(caller.ID, caller.USER_ID, caller.ROLE, caller.JOINED_AT,
                target.ID, target.USER_ID, target.ROLE, target.JOINED_AT, otherMembers, otherAdmins)
            .from(caller).leftJoin(target).on(target.GROUP_ID.eq(group.id()).and(target.ID.eq(membershipId))
                .and(caller.ROLE.eq(GroupProjections.toDatabase(GroupRole.ADMIN)).or(target.ID.eq(caller.ID))))
            .where(caller.GROUP_ID.eq(group.id()).and(caller.USER_ID.eq(userId)))
            .fetchOptional(row -> new MembershipChangeContext(GroupProjections.member(row, caller),
                Optional.ofNullable(row.get(target.ID)).map(id -> GroupProjections.member(row, target)),
                row.get(otherMembers), row.get(otherAdmins)));
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

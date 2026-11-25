package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUPS_;
import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.GroupReadPort;
import de.hsh.favs.sharewise.groups.internal.application.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.application.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.groups.internal.persistence.generated.tables.GroupMembers;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * Member-scoped projections, each assembled by one statement: the caller's own membership row
 * selects the visible groups, so a non-member and a missing group yield the same empty result.
 */
@Repository
@Scope("operation")
class JooqGroupReads implements GroupReadPort {

    private static final GroupMembers MEMBERSHIP = GROUP_MEMBERS.as("membership");
    private static final Field<GroupRole> MEMBERSHIP_ROLE = GroupProjections.role(MEMBERSHIP).as("caller_role");

    private final JooqAccess database;

    JooqGroupReads(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<GroupSummary> listForMember(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return database.dsl()
                .select(GROUPS_.ID, GROUPS_.NAME, GROUPS_.DESCRIPTION, GroupProjections.MEMBER_COUNT, MEMBERSHIP_ROLE)
                .from(MEMBERSHIP)
                .join(GROUPS_).on(GROUPS_.ID.eq(MEMBERSHIP.GROUP_ID))
                .where(MEMBERSHIP.USER_ID.eq(userId))
                .orderBy(DSL.lower(GROUPS_.NAME), GROUPS_.ID)
                .fetch(row -> GroupProjections.summary(row, MEMBERSHIP_ROLE));
    }

    @Override
    public Optional<GroupDetails> findForMember(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        return database.dsl()
                .select(GROUPS_.ID, GROUPS_.NAME, GROUPS_.DESCRIPTION, GROUPS_.CREATED_AT, GROUPS_.VERSION,
                        GroupProjections.MEMBER_COUNT, MEMBERSHIP_ROLE)
                .from(MEMBERSHIP)
                .join(GROUPS_).on(GROUPS_.ID.eq(MEMBERSHIP.GROUP_ID))
                .where(MEMBERSHIP.GROUP_ID.eq(groupId).and(MEMBERSHIP.USER_ID.eq(userId)))
                .fetchOptional(row -> GroupProjections.details(row, MEMBERSHIP_ROLE));
    }

    @Override
    public Optional<GroupWorkspace> findWorkspace(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        Field<List<GroupMember>> roster = DSL.multiset(DSL.select(GROUP_MEMBERS.ID, GROUP_MEMBERS.USER_ID,
                GROUP_MEMBERS.ROLE, GROUP_MEMBERS.JOINED_AT)
            .from(GROUP_MEMBERS).where(GROUP_MEMBERS.GROUP_ID.eq(GROUPS_.ID))
            .orderBy(GROUP_MEMBERS.JOINED_AT, GROUP_MEMBERS.ID))
            .convertFrom(rows -> rows.map(row -> GroupProjections.member(row, GROUP_MEMBERS)));
        return database.dsl()
            .select(GROUPS_.ID, GROUPS_.NAME, GROUPS_.DESCRIPTION, GROUPS_.CREATED_AT, GROUPS_.VERSION,
                MEMBERSHIP_ROLE, MEMBERSHIP.ID, roster)
            .from(MEMBERSHIP).join(GROUPS_).on(GROUPS_.ID.eq(MEMBERSHIP.GROUP_ID))
            .where(MEMBERSHIP.GROUP_ID.eq(groupId).and(MEMBERSHIP.USER_ID.eq(userId)))
            .fetchOptional(row -> {
                List<GroupMember> members = row.get(roster);
                return new GroupWorkspace(GroupProjections.details(row, MEMBERSHIP_ROLE, members.size()),
                    row.get(MEMBERSHIP.ID), members);
            });
    }
}

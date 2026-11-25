package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUPS_;
import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.groups.internal.persistence.generated.tables.GroupMembers;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * Shared SQL fragments and row mappings for member-scoped group projections, so that list,
 * detail and update statements compute the member count and the caller's role identically.
 */
final class GroupProjections {

    private static final GroupMembers COUNTED = GROUP_MEMBERS.as("counted");
    private static final GroupMembers CALLER = GROUP_MEMBERS.as("caller");

    /** Number of current members of the group in the enclosing {@code groups.groups} row. */
    static final Field<Integer> MEMBER_COUNT = DSL.field(
            DSL.selectCount().from(COUNTED).where(COUNTED.GROUP_ID.eq(GROUPS_.ID))).as("member_count");

    private GroupProjections() {
    }

    /** A membership role column, read as the domain role. */
    static Field<GroupRole> role(GroupMembers members) {
        return members.ROLE.convertFrom(GroupProjections::toDomain);
    }

    /** The user's role in the group of the enclosing row; {@code NULL} if the user is not a member. */
    static Field<GroupRole> callerRole(UUID userId) {
        return DSL.field(DSL.select(CALLER.ROLE).from(CALLER)
                .where(CALLER.GROUP_ID.eq(GROUPS_.ID).and(CALLER.USER_ID.eq(userId))))
            .convertFrom(GroupProjections::toDomain)
            .as("caller_role");
    }

    static GroupDetails details(Record row, Field<GroupRole> callerRole) {
        return new GroupDetails(row.get(GROUPS_.ID), metadata(row), row.get(GROUPS_.CREATED_AT).toInstant(),
                row.get(GROUPS_.VERSION), row.get(MEMBER_COUNT), row.get(callerRole));
    }

    static GroupSummary summary(Record row, Field<GroupRole> callerRole) {
        return new GroupSummary(row.get(GROUPS_.ID), metadata(row), row.get(MEMBER_COUNT), row.get(callerRole));
    }

    static de.hsh.favs.sharewise.groups.internal.persistence.generated.enums.GroupRole toDatabase(GroupRole role) {
        return switch (role) {
            case MEMBER -> de.hsh.favs.sharewise.groups.internal.persistence.generated.enums.GroupRole.member;
            case ADMIN -> de.hsh.favs.sharewise.groups.internal.persistence.generated.enums.GroupRole.admin;
        };
    }

    private static GroupRole toDomain(de.hsh.favs.sharewise.groups.internal.persistence.generated.enums.GroupRole role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case member -> GroupRole.MEMBER;
            case admin -> GroupRole.ADMIN;
        };
    }

    /** Stored values were validated on write; the domain constructors reject anything else. */
    private static GroupMetadata metadata(Record row) {
        return new GroupMetadata(new GroupName(row.get(GROUPS_.NAME)),
                Optional.ofNullable(row.get(GROUPS_.DESCRIPTION)).map(GroupDescription::new));
    }
}

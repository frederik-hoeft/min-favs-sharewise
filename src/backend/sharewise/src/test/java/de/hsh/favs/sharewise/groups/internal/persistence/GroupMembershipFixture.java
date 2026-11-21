package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Test-only direct SQL for membership states that P2.2a offers no operation for; member
 * management arrives with P2.2b. Local users are seeded through the provisioning port instead.
 *
 * <p>Use it inside an application segment ({@code JooqAccess.dsl()}) for Integration tests, or
 * on an independently committed connection ({@code DSL.using(connection, SQLDialect.POSTGRES)})
 * for E2E tests.
 */
final class GroupMembershipFixture {

    private GroupMembershipFixture() {
    }

    static void addMember(DSLContext db, UUID groupId, UUID userId, GroupRole role) {
        Objects.requireNonNull(role, "role");
        db.insertInto(GROUP_MEMBERS)
            .set(GROUP_MEMBERS.GROUP_ID, Objects.requireNonNull(groupId, "groupId"))
            .set(GROUP_MEMBERS.USER_ID, Objects.requireNonNull(userId, "userId"))
            .set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(role))
            .execute();
    }

    static void changeRole(DSLContext db, UUID groupId, UUID userId, GroupRole role) {
        int changed = db.update(GROUP_MEMBERS)
            .set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(Objects.requireNonNull(role, "role")))
            .where(GROUP_MEMBERS.GROUP_ID.eq(groupId).and(GROUP_MEMBERS.USER_ID.eq(userId)))
            .execute();
        if (changed != 1) {
            throw new IllegalStateException("Expected exactly one membership of " + userId + " in " + groupId);
        }
    }
}

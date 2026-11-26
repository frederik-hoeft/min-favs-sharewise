package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.access.GroupUserRegistry;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.operation.OperationTestHarness;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * Test-only setup of local Group users and membership state.
 *
 * <p>Local users are provisioned through the production {@link GroupUserRegistry} port, the
 * write path available to future event projection and IAM lookup. Direct SQL establishes
 * membership and role preconditions independently of the application operation under test.
 * The {@link DSLContext} variants run inside an application segment ({@code JooqAccess.dsl()}) for Integration tests or
 * on an independently committed connection ({@code DSL.using(connection, SQLDialect.POSTGRES)});
 * the {@link IntegrationTestContext} variants commit on their own for E2E tests.
 */
public final class GroupTestFixture {

    private GroupTestFixture() {
    }

    /** Provisions the users through the registry port in one committed segment. */
    public static void provision(IntegrationTestContext fixture, UUID... users) {
        Objects.requireNonNull(fixture, "fixture");
        List<UUID> provisioned = List.of(users);
        new OperationTestHarness(fixture.context().getBean(ServiceScopeFactory.class)).inOperationScope(scope -> {
            GroupUserRegistry registry = scope.resolve(GroupUserRegistry.class);
            return scope.resolve(Transactions.class).execute(() -> {
                for (UUID user : provisioned) {
                    registry.provision(user);
                }
                return TransactionOutcome.commit(provisioned.size());
            });
        });
    }

    /** Commits an additional membership on an independent application-role connection. */
    public static void addMember(IntegrationTestContext fixture, UUID groupId, UUID userId, GroupRole role)
            throws SQLException {
        Objects.requireNonNull(fixture, "fixture");
        IntegrationTestContext.seedCommitted(fixture.database(),
            connection -> addMember(DSL.using(connection, SQLDialect.POSTGRES), groupId, userId, role));
    }

    public static void addMember(DSLContext db, UUID groupId, UUID userId, GroupRole role) {
        Objects.requireNonNull(role, "role");
        db.insertInto(GROUP_MEMBERS)
            .set(GROUP_MEMBERS.GROUP_ID, Objects.requireNonNull(groupId, "groupId"))
            .set(GROUP_MEMBERS.USER_ID, Objects.requireNonNull(userId, "userId"))
            .set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(role))
            .execute();
    }

    public static void changeRole(DSLContext db, UUID groupId, UUID userId, GroupRole role) {
        int changed = db.update(GROUP_MEMBERS)
            .set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(Objects.requireNonNull(role, "role")))
            .where(GROUP_MEMBERS.GROUP_ID.eq(groupId).and(GROUP_MEMBERS.USER_ID.eq(userId)))
            .execute();
        if (changed != 1) {
            throw new IllegalStateException("Expected exactly one membership of " + userId + " in " + groupId);
        }
    }
}

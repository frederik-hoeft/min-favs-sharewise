package de.hsh.favs.sharewise.groups.internal.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.access.GroupLockPort;
import de.hsh.favs.sharewise.groups.internal.application.access.GroupUserRegistry;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMutationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupReadPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationIssuanceMaterial;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationListing;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemptionContext;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationSummary;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipPort;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeContext;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/**
 * Group jOOQ adapters against the migrated PostgreSQL schema, each test in one rollback-owned
 * segment (GM-T02, GM-T06, GM-T07, GM-T16). Durable effects are covered by the E2E tests.
 */
@Tag("integration")
class GroupPersistenceIT extends IntegrationTestBase {

    private static final GroupMetadata TRIP = metadata("Trip", null);

    @Test
    void provisioningTheSameUserTwiceKeepsOneRow() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            UUID user = UUID.randomUUID();

            boolean known = transactions.execute(() -> {
                boolean before = users.isKnown(user);
                users.provision(user);
                users.provision(user);
                return TransactionOutcome.commit(!before && users.isKnown(user));
            });

            assertTrue(known);
            Integer rows = scope.inspect(sql -> sql.queryForObject(
                "SELECT count(*) FROM groups.users WHERE id = ?", Integer.class, user));
            assertEquals(1, rows);
        });
    }

    @Test
    void creationStoresTheGroupAndExactlyOneAdministratorMembership() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            UUID creator = UUID.randomUUID();
            GroupMetadata metadata = metadata("Flat share", "Rent");

            GroupDetails created = transactions.execute(() -> {
                users.provision(creator);
                return TransactionOutcome.commit(creation.createWithAdministrator(creator, metadata));
            });

            assertEquals(metadata, created.metadata());
            assertEquals(0, created.version());
            assertEquals(1, created.memberCount());
            assertEquals(GroupRole.ADMIN, created.callerRole());
            assertEquals(List.of("Flat share|Rent|0"), scope.inspect(sql -> sql.query(
                "SELECT name, description, version FROM groups.groups WHERE id = ?",
                (row, index) -> row.getString(1) + "|" + row.getString(2) + "|" + row.getLong(3), created.id())));
            assertEquals(List.of(creator + "|admin"), scope.inspect(sql -> sql.query(
                "SELECT user_id, role FROM groups.group_members WHERE group_id = ?",
                (row, index) -> row.getString(1) + "|" + row.getString(2), created.id())));
        });
    }

    @Test
    void creationForAnUnknownCreatorViolatesTheMembershipForeignKey() {
        runTransactionTest((transactions, scope) -> {
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);

            RuntimeException failure = assertThrows(RuntimeException.class, () -> transactions.execute(() ->
                TransactionOutcome.commit(creation.createWithAdministrator(UUID.randomUUID(), TRIP))));

            assertEquals("23503", sqlState(failure));
            assertEquals(failure, scope.observation().failure());
        });
    }

    @Test
    void listContainsExactlyTheMembersGroupsOrderedByCaseInsensitiveNameThenId() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            GroupReadPort reads = scope.resolve(GroupReadPort.class);
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID loner = UUID.randomUUID();

            List<List<GroupSummary>> lists = transactions.execute(() -> {
                users.provision(alice);
                users.provision(bob);
                users.provision(loner);
                UUID beta = creation.createWithAdministrator(alice, metadata("beta", "second")).id();
                UUID alpha = creation.createWithAdministrator(alice, metadata("Alpha", null)).id();
                UUID gamma = creation.createWithAdministrator(bob, metadata("Gamma", null)).id();
                creation.createWithAdministrator(bob, metadata("Bob only", null));
                GroupTestFixture.addMember(jooq.dsl(), gamma, alice, GroupRole.MEMBER);
                assertEquals(Optional.empty(), reads.listForMember(UUID.randomUUID()));
                List<GroupSummary> aliceGroups = reads.listForMember(alice).orElseThrow();
                assertEquals(List.of(alpha, beta, gamma), aliceGroups.stream().map(GroupSummary::id).toList());
                return TransactionOutcome.commit(List.of(aliceGroups, reads.listForMember(loner).orElseThrow()));
            });

            List<GroupSummary> aliceGroups = lists.get(0);
            assertEquals(List.of(GroupRole.ADMIN, GroupRole.ADMIN, GroupRole.MEMBER),
                aliceGroups.stream().map(GroupSummary::callerRole).toList());
            assertEquals(List.of(1, 1, 2), aliceGroups.stream().map(GroupSummary::memberCount).toList());
            assertEquals(metadata("beta", "second"), aliceGroups.get(1).metadata());
            assertEquals(List.of(), lists.get(1));
        });
    }

    @Test
    void equalNamesAreOrderedById() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            GroupReadPort reads = scope.resolve(GroupReadPort.class);
            UUID user = UUID.randomUUID();

            List<UUID> ordered = transactions.execute(() -> {
                users.provision(user);
                for (String name : List.of("same", "Same", "SAME")) {
                    creation.createWithAdministrator(user, metadata(name, null));
                }
                return TransactionOutcome.commit(reads.listForMember(user).orElseThrow().stream().map(GroupSummary::id).toList());
            });

            assertEquals(ordered.stream().sorted().toList(), ordered);
        });
    }

    @Test
    void workspaceProjectionAndMembershipWritesShareCurrentRosterAndKeepMetadataVersion() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            GroupReadPort reads = scope.resolve(GroupReadPort.class);
            GroupLockPort locks = scope.resolve(GroupLockPort.class);
            GroupMembershipPort memberships = scope.resolve(GroupMembershipPort.class);
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            transactions.execute(() -> {
                users.provision(alice);
                users.provision(bob);
                UUID group = creation.createWithAdministrator(alice, TRIP).id();
                UUID foreign = creation.createWithAdministrator(bob, TRIP).id();
                GroupTestFixture.addMember(jooq.dsl(), group, bob, GroupRole.MEMBER);
                GroupWorkspace snapshot = reads.findWorkspace(group, bob).orElseThrow();
                assertEquals(2, snapshot.group().memberCount());
                assertEquals(GroupRole.MEMBER, snapshot.group().callerRole());
                assertEquals(java.util.Set.of(alice, bob), java.util.Set.copyOf(snapshot.members().stream().map(GroupMember::userId).toList()));
                assertEquals(snapshot.members().stream().sorted(java.util.Comparator.comparing(GroupMember::joinedAt)
                    .thenComparing(member -> member.id().toString())).toList(), snapshot.members());
                assertEquals(Optional.empty(), reads.findWorkspace(foreign, alice));
                assertEquals(Optional.empty(), reads.findWorkspace(UUID.randomUUID(), alice));
                LockedGroup locked = locks.lockGroup(group, alice).orElseThrow();
                UUID target = snapshot.callerMembershipId();
                assertEquals(Optional.empty(), memberships.changeContext(locked, alice,
                    reads.findWorkspace(foreign, bob).orElseThrow().callerMembershipId()).orElseThrow().target());
                MembershipChangeContext before = memberships.changeContext(locked, alice, target).orElseThrow();
                assertEquals(GroupRole.ADMIN, before.caller().role());
                assertEquals(GroupRole.MEMBER, before.target().orElseThrow().role());
                assertTrue(before.hasOtherMembers());
                assertTrue(before.hasOtherAdministrators());
                assertFalse(memberships.changeContext(locked, alice,
                    before.caller().id()).orElseThrow().hasOtherAdministrators());
                memberships.setRole(locked, target, GroupRole.ADMIN);
                GroupWorkspace promoted = reads.findWorkspace(group, bob).orElseThrow();
                assertEquals(GroupRole.ADMIN, promoted.group().callerRole());
                assertEquals(target, promoted.callerMembershipId());
                assertEquals(0, promoted.group().version());
                assertTrue(memberships.changeContext(locked, alice, before.caller().id()).orElseThrow().hasOtherAdministrators());
                memberships.removeMember(locked, target);
                assertEquals(Optional.empty(), reads.findWorkspace(group, bob));
                assertEquals(1, reads.findWorkspace(group, alice).orElseThrow().group().memberCount());
                return TransactionOutcome.abstain(true);
            });
        });
    }

    @Test
    void consolidatedProjectionsEachExecuteOneStatementAndScopeTheirData() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            JooqAccess actual = scope.resolve(JooqAccess.class);
            AtomicInteger statements = new AtomicInteger();
            // Append to the real segment-bound configuration, preserving its lifetime guards.
            JooqAccess counted = () -> actual.dsl().configuration().deriveAppending(
                new DefaultExecuteListenerProvider(new ExecuteListener() {
                    @Override public void executeStart(ExecuteContext context) {
                        statements.incrementAndGet();
                    }
                })).dsl();
            JooqGroupReads reads = new JooqGroupReads(counted);
            JooqGroupMutations mutations = new JooqGroupMutations(counted);
            JooqGroupMemberships members = new JooqGroupMemberships(counted);
            JooqGroupInvitations invitations = new JooqGroupInvitations(counted);
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID outsider = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            transactions.execute(() -> {
                users.provision(admin);
                users.provision(member);
                users.provision(outsider);
                UUID group = creation.createWithAdministrator(admin, TRIP).id();
                GroupTestFixture.addMember(actual.dsl(), group, member, GroupRole.MEMBER);
                assertEquals(1, reads.listForMember(admin).orElseThrow().size());
                assertEquals(1, statements.getAndSet(0));
                assertEquals(List.of(), reads.listForMember(outsider).orElseThrow());
                assertEquals(1, statements.getAndSet(0));
                assertEquals(Optional.empty(), reads.listForMember(unknown));
                assertEquals(1, statements.getAndSet(0));
                assertEquals(group, reads.findForMember(group, member).orElseThrow().id());
                assertEquals(1, statements.getAndSet(0));
                GroupWorkspace workspace = reads.findWorkspace(group, member).orElseThrow();
                assertEquals(1, statements.getAndSet(0));
                LockedGroup locked = mutations.lockGroup(group, admin).orElseThrow();
                assertEquals(1, statements.getAndSet(0));
                MembershipChangeContext context = members.changeContext(locked, admin, workspace.callerMembershipId()).orElseThrow();
                assertEquals(GroupRole.MEMBER, context.target().orElseThrow().role());
                assertTrue(context.hasOtherMembers());
                assertTrue(context.hasOtherAdministrators());
                assertEquals(1, statements.getAndSet(0));
                MembershipChangeContext denied = members.changeContext(locked, member, context.caller().id()).orElseThrow();
                assertEquals(GroupRole.MEMBER, denied.caller().role());
                assertEquals(Optional.empty(), denied.target());
                assertFalse(denied.hasOtherMembers());
                assertFalse(denied.hasOtherAdministrators());
                assertEquals(1, statements.getAndSet(0));
                assertEquals(List.of(), invitations.listForMember(group, admin).orElseThrow().invitations());
                assertEquals(1, statements.getAndSet(0));
                InvitationIssuanceMaterial material = invitations.issuanceMaterial(locked);
                assertEquals(1, statements.getAndSet(0));
                InvitationSummary invitation = invitations.insert(locked, material.selector(), new byte[32],
                    material.createdAt(), null, 2);
                assertEquals(1, statements.getAndSet(0));
                assertEquals(List.of(invitation), invitations.listForMember(group, admin).orElseThrow().invitations());
                assertEquals(1, statements.getAndSet(0));
                InvitationListing ordinary = invitations.listForMember(group, member).orElseThrow();
                assertEquals(GroupRole.MEMBER, ordinary.callerRole());
                assertEquals(List.of(), ordinary.invitations());
                assertEquals(1, statements.getAndSet(0));
                assertEquals(Optional.empty(), invitations.listForMember(group, outsider));
                assertEquals(1, statements.getAndSet(0));
                assertEquals(TRIP.name().value(), invitations.previewContext(material.selector(), outsider).orElseThrow().groupName());
                assertEquals(1, statements.getAndSet(0));
                assertEquals(Optional.empty(), invitations.previewContext(material.selector(), unknown));
                assertEquals(1, statements.getAndSet(0));
                assertEquals(Optional.empty(), invitations.lockGroupForRedemption(material.selector(), unknown));
                assertEquals(1, statements.getAndSet(0));
                LockedGroup redemptionLock = invitations.lockGroupForRedemption(material.selector(), outsider).orElseThrow();
                assertEquals(locked, redemptionLock);
                assertEquals(1, statements.getAndSet(0));
                InvitationRedemptionContext redemption = invitations.redemptionContext(redemptionLock, material.selector(), outsider).orElseThrow();
                assertEquals(invitation, redemption.invitation().summary());
                assertFalse(redemption.alreadyMember());
                assertFalse(redemption.evaluatedAt().isBefore(material.createdAt()));
                assertEquals(1, statements.getAndSet(0));
                assertTrue(invitations.redemptionContext(redemptionLock, material.selector(), member).orElseThrow().alreadyMember());
                assertEquals(1, statements.getAndSet(0));
                return TransactionOutcome.abstain(true);
            });
        });
    }

    @Test
    void detailsAreVisibleOnlyToMembers() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            GroupReadPort reads = scope.resolve(GroupReadPort.class);
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();

            transactions.execute(() -> {
                users.provision(admin);
                users.provision(member);
                users.provision(stranger);
                GroupDetails created = creation.createWithAdministrator(admin, TRIP);
                GroupTestFixture.addMember(jooq.dsl(), created.id(), member, GroupRole.MEMBER);

                GroupDetails seenByMember = reads.findForMember(created.id(), member).orElseThrow();
                assertEquals(GroupRole.MEMBER, seenByMember.callerRole());
                assertEquals(2, seenByMember.memberCount());
                assertEquals(created.createdAt(), seenByMember.createdAt());
                assertEquals(GroupRole.ADMIN, reads.findForMember(created.id(), admin).orElseThrow().callerRole());
                assertEquals(Optional.empty(), reads.findForMember(created.id(), stranger));
                assertEquals(Optional.empty(), reads.findForMember(UUID.randomUUID(), member));
                return TransactionOutcome.abstain(true);
            });

            assertEquals(TransactionDecision.ABSTAIN, scope.observation().requestedDecision());
        });
    }

    @Test
    void lockedReplacementUpdatesMetadataAndIncrementsTheVersion() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            GroupLockPort locks = scope.resolve(GroupLockPort.class);
            GroupMutationPort mutations = scope.resolve(GroupMutationPort.class);
            GroupMembershipPort memberships = scope.resolve(GroupMembershipPort.class);
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            UUID admin = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();

            GroupDetails updated = transactions.execute(() -> {
                users.provision(admin);
                users.provision(member);
                users.provision(stranger);
                UUID group = creation.createWithAdministrator(admin, metadata("Trip", "Old")).id();
                GroupTestFixture.addMember(jooq.dsl(), group, member, GroupRole.MEMBER);

                assertEquals(Optional.empty(), locks.lockGroup(UUID.randomUUID(), admin));
                assertEquals(Optional.empty(), locks.lockGroup(group, UUID.randomUUID()));
                LockedGroup locked = locks.lockGroup(group, admin).orElseThrow();
                assertEquals(new LockedGroup(group, 0), locked);
                assertEquals(Optional.of(GroupRole.ADMIN), memberships.memberForUser(locked, admin).map(GroupMember::role));
                assertEquals(Optional.of(GroupRole.MEMBER), memberships.memberForUser(locked, member).map(GroupMember::role));
                assertEquals(Optional.empty(), memberships.memberForUser(locked, stranger));
                return TransactionOutcome.commit(mutations.replaceMetadata(locked, admin, metadata("Summer trip", null)));
            });

            assertEquals(metadata("Summer trip", null), updated.metadata());
            assertEquals(1, updated.version());
            assertEquals(2, updated.memberCount());
            assertEquals(GroupRole.ADMIN, updated.callerRole());
            assertEquals(List.of("Summer trip|null|1"), scope.inspect(sql -> sql.query(
                "SELECT name, description, version FROM groups.groups WHERE id = ?",
                (row, index) -> row.getString(1) + "|" + row.getString(2) + "|" + row.getLong(3), updated.id())));
        });
    }

    @Test
    void replacementWithAStaleLockedVersionIsAProtocolViolation() {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            GroupMutationPort mutations = scope.resolve(GroupMutationPort.class);
            UUID admin = UUID.randomUUID();

            // @Repository translation wraps the adapter's IllegalStateException; it remains an
            // unexpected failure that rolls the segment back.
            InvalidDataAccessApiUsageException failure = assertThrows(InvalidDataAccessApiUsageException.class,
                () -> transactions.execute(() -> {
                users.provision(admin);
                UUID group = creation.createWithAdministrator(admin, TRIP).id();
                LockedGroup stale = new LockedGroup(group, 7);
                return TransactionOutcome.commit(mutations.replaceMetadata(stale, admin, TRIP));
            }));
            assertTrue(failure.getCause() instanceof IllegalStateException, failure::toString);
        });
    }

    /**
     * Database backstops for GM-09 and the key constraints, bypassing application validation.
     * {@code {group}} and {@code {user}} name an existing group and its administrator.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
        "empty name;            INSERT INTO groups.groups (name) VALUES ('');                                    23514",
        "blank name;            INSERT INTO groups.groups (name) VALUES ('   ');                                 23514",
        "tab-only name;         INSERT INTO groups.groups (name) VALUES (chr(9));                               23514",
        "padded name;           INSERT INTO groups.groups (name) VALUES (' padded ');                          23514",
        "embedded name tab;     INSERT INTO groups.groups (name) VALUES ('a' || chr(9) || 'b');                  23514",
        "empty description;     INSERT INTO groups.groups (name, description) VALUES ('x', '');                 23514",
        "blank description;     INSERT INTO groups.groups (name, description) VALUES ('x', '   ');              23514",
        "padded description;    INSERT INTO groups.groups (name, description) VALUES ('x', ' padded ');         23514",
        "description tab;       INSERT INTO groups.groups (name, description) VALUES ('x', 'a' || chr(9) || 'b'); 23514",
        "lone CR description;   INSERT INTO groups.groups (name, description) VALUES ('x', 'a' || chr(13) || 'b'); 23514",
        "padded name update;    UPDATE groups.groups SET name = ' padded ' WHERE id = '{group}';                 23514",
        "empty text update;     UPDATE groups.groups SET description = '' WHERE id = '{group}';                23514",
        "missing name;          INSERT INTO groups.groups (name) VALUES (NULL);                                  23502",
        "65-character name;     INSERT INTO groups.groups (name) VALUES (repeat('x', 65));                       22001",
        "513-character text;    INSERT INTO groups.groups (name, description) VALUES ('x', repeat('d', 513));    22001",
        "negative version;      INSERT INTO groups.groups (name, version) VALUES ('x', -1);                      23514",
        "duplicate membership;  INSERT INTO groups.group_members (group_id, user_id, role) VALUES ('{group}', '{user}', 'member'); 23505",
        "unknown member user;   INSERT INTO groups.group_members (group_id, user_id, role) VALUES ('{group}', gen_random_uuid(), 'member'); 23503",
        "unknown member group;  INSERT INTO groups.group_members (group_id, user_id, role) VALUES (gen_random_uuid(), '{user}', 'member'); 23503",
        "unknown role;          INSERT INTO groups.group_members (group_id, user_id, role) VALUES ('{group}', '{user}', 'owner'); 22P02",
    })
    void databaseConstraintsRejectInvalidRows(String description, String statement, String expectedSqlState) {
        runTransactionTest((transactions, scope) -> {
            GroupUserRegistry users = scope.resolve(GroupUserRegistry.class);
            GroupCreationPort creation = scope.resolve(GroupCreationPort.class);
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            UUID user = UUID.randomUUID();

            RuntimeException failure = assertThrows(RuntimeException.class, () -> transactions.execute(() -> {
                users.provision(user);
                UUID group = creation.createWithAdministrator(user, TRIP).id();
                DSLContext db = jooq.dsl();
                db.execute(statement.replace("{group}", group.toString()).replace("{user}", user.toString()));
                return TransactionOutcome.commit(true);
            }));

            assertEquals(expectedSqlState, sqlState(failure), description);
        });
    }

    @Test
    void databaseAcceptsNormalizedMultilineAndUnicodeMetadata() {
        runTransactionTest((transactions, scope) -> {
            JooqAccess jooq = scope.resolve(JooqAccess.class);
            transactions.execute(() -> {
                DSLContext db = jooq.dsl();
                String name = "🚀".repeat(64);
                for (String description : List.of("a\nb", "a\r\nb", "é".repeat(512))) {
                    assertEquals(1, db.execute("INSERT INTO groups.groups (name, description) VALUES (?, ?)",
                        name, description));
                }
                assertEquals(1, db.execute("INSERT INTO groups.groups (name, description) VALUES (?, NULL)", name));
                return TransactionOutcome.abstain(true);
            });
        });
    }

    @Test
    void migrationRoleOwnsTheTablesAndTheApplicationRoleMayOnlyUseThem() {
        runTransactionTest((transactions, scope) -> {
            JooqAccess jooq = scope.resolve(JooqAccess.class);

            transactions.execute(() -> {
                DSLContext db = jooq.dsl();
                List<String> tables = db.fetch("SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'groups'")
                    .map(row -> row.get(0) + "|" + row.get(1));
                assertTrue(tables.containsAll(List.of("group_members|sharewise_migrator", "groups|sharewise_migrator",
                        "users|sharewise_migrator", "invitations|sharewise_migrator")));
                assertTrue(tables.stream().allMatch(table -> table.endsWith("|sharewise_migrator")));
                // These core tables retain the original default grants. Invitation-specific
                // column privileges are covered by GroupInvitationSchemaE2E.
                for (String table : List.of("groups.users", "groups.groups", "groups.group_members")) {
                    for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                        assertEquals(Boolean.TRUE, db.fetchValue(
                            "SELECT has_table_privilege('sharewise_app', ?, ?)", table, privilege), table + " " + privilege);
                    }
                    assertEquals(Boolean.FALSE, db.fetchValue(
                        "SELECT has_table_privilege('sharewise_app', ?, 'TRUNCATE, REFERENCES, TRIGGER')", table), table);
                }
                assertEquals(Boolean.FALSE, db.fetchValue("SELECT has_schema_privilege('sharewise_app', 'groups', 'CREATE')"));
                assertEquals(Boolean.TRUE, db.fetchValue(
                    "SELECT has_type_privilege('sharewise_app', 'groups.group_role', 'USAGE')"));
                assertEquals(Boolean.FALSE, db.fetchValue("""
                    SELECT EXISTS (SELECT 1 FROM aclexplode((SELECT typacl FROM pg_type
                        WHERE oid = 'groups.group_role'::regtype)) WHERE grantee = 0)"""),
                    "PUBLIC must not use the role type");
                return TransactionOutcome.abstain(true);
            });
        });
    }

    private static GroupMetadata metadata(String name, String description) {
        return new GroupMetadata(new GroupName(name), Optional.ofNullable(description).map(GroupDescription::new));
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
        }
        throw new AssertionError("No SQL state in " + failure, failure);
    }
}

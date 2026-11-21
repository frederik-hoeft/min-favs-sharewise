package de.hsh.favs.sharewise.groups.internal.application;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.anonymously;
import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Group application services through the real operation scope and dispatcher, with the
 * test-only caller identity and in-memory ports (GM-02, GM-03, GM-05 to GM-08).
 */
@ComponentTest
@SpringJUnitConfig({OperationScopeTestComponents.class, IdentityTestConfiguration.class,
    GroupApplicationTestConfiguration.class})
class GroupApplicationServiceTests {

    private static final UUID ALICE = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be201");
    private static final UUID BOB = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be202");
    private static final UUID STRANGER = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be203");
    private static final UUID UNKNOWN = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be204");
    private static final GroupMetadata TRIP = new GroupMetadata(new GroupName("Trip"), Optional.empty());

    @Autowired OperationDispatcher dispatcher;
    @Autowired InMemoryGroups store;
    @Autowired RecordingTransactions.Log transactions;

    @BeforeEach
    void reset() {
        store.reset();
        transactions.reset();
        store.provision(ALICE);
        store.provision(BOB);
        store.provision(STRANGER);
        store.clearCalls();
    }

    @Test
    void creatorBecomesTheOnlyAdministratorAndTheSegmentCommits() {
        Result<GroupDetails, GroupFailure> result = create(ALICE, new CreateGroupCommand("  Flat share ", " Rent "));

        GroupDetails created = assertOk(result);
        assertEquals(new GroupMetadata(new GroupName("Flat share"), Optional.of(new GroupDescription("Rent"))),
            created.metadata());
        assertEquals(GroupRole.ADMIN, created.callerRole());
        assertEquals(1, created.memberCount());
        assertEquals(0, created.version());
        assertEquals(Map.of(ALICE, GroupRole.ADMIN), store.stored(created.id()).orElseThrow().members());
        assertEquals(List.of(TransactionDecision.COMMIT), transactions.decisions());
    }

    @Test
    void invalidMetadataIsRejectedBeforeAnyDatabaseWork() {
        Result<GroupDetails, GroupFailure> result = create(ALICE, new CreateGroupCommand(" ", "d".repeat(513)));

        assertEquals(Result.err(new GroupFailure.InvalidMetadata(List.of(
            new MetadataViolation(MetadataViolation.Field.NAME, MetadataViolation.Reason.REQUIRED),
            new MetadataViolation(MetadataViolation.Field.DESCRIPTION, MetadataViolation.Reason.TOO_LONG)))), result);
        assertNoDatabaseWork();
    }

    @Test
    void unauthenticatedCallersFailBeforeValidationOrDatabaseWork() {
        assertThrows(UnauthenticatedCallerException.class, () -> anonymously(() ->
            dispatcher.dispatch(GroupCreationService.class, service -> service.create(new CreateGroupCommand("", null)))));
        assertThrows(UnauthenticatedCallerException.class, () -> anonymously(() ->
            dispatcher.dispatch(GroupQueryService.class, GroupQueryService::listMine)));
        assertThrows(UnauthenticatedCallerException.class, () -> anonymously(() ->
            dispatcher.dispatch(GroupQueryService.class, service -> service.get(UUID.randomUUID()))));
        assertThrows(UnauthenticatedCallerException.class, () -> anonymously(() ->
            dispatcher.dispatch(GroupMetadataService.class, service ->
                service.replace(new ReplaceGroupMetadataCommand(UUID.randomUUID(), "", null, 0)))));
        assertNoDatabaseWork();
    }

    @Test
    void unknownLocalUsersAreRejectedByEveryOperationWithoutWrites() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN));

        assertEquals(Result.err(new GroupFailure.UnknownUser()), create(UNKNOWN, new CreateGroupCommand("Trip", null)));
        assertEquals(Result.err(new GroupFailure.UnknownUser()), list(UNKNOWN));
        assertEquals(Result.err(new GroupFailure.UnknownUser()), get(UNKNOWN, group));
        assertEquals(Result.err(new GroupFailure.UnknownUser()), replace(UNKNOWN, group, "Renamed", 0));

        assertEquals(0, store.writes());
        assertFalse(store.calls().contains("create"));
        assertEquals(List.of(TransactionDecision.ROLLBACK, TransactionDecision.ROLLBACK, TransactionDecision.ROLLBACK,
            TransactionDecision.ROLLBACK), transactions.decisions());
    }

    @Test
    void listReturnsOnlyTheCallersGroupsWithTheirRoles() {
        UUID trip = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER));
        UUID flat = store.seedGroup(new GroupMetadata(new GroupName("apartment"), Optional.empty()), 0,
            Map.of(BOB, GroupRole.ADMIN));
        store.seedGroup(new GroupMetadata(new GroupName("Other"), Optional.empty()), 0, Map.of(ALICE, GroupRole.ADMIN));

        List<GroupSummary> groups = assertOk(list(BOB));

        assertEquals(List.of(flat, trip), groups.stream().map(GroupSummary::id).toList());
        assertEquals(List.of(GroupRole.ADMIN, GroupRole.MEMBER), groups.stream().map(GroupSummary::callerRole).toList());
        assertEquals(List.of(1, 2), groups.stream().map(GroupSummary::memberCount).toList());
        assertEquals(List.of(), assertOk(list(STRANGER)));
        assertEquals(List.of(TransactionDecision.ABSTAIN, TransactionDecision.ABSTAIN), transactions.decisions());
    }

    @Test
    void missingGroupsAndNonMembersAreIndistinguishable() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER));

        assertEquals(GroupRole.MEMBER, assertOk(get(BOB, group)).callerRole());
        assertEquals(Result.err(new GroupFailure.GroupNotFound()), get(STRANGER, group));
        assertEquals(Result.err(new GroupFailure.GroupNotFound()), get(ALICE, UUID.randomUUID()));
        assertEquals(Result.err(new GroupFailure.GroupNotFound()), replace(STRANGER, group, "Renamed", 0));
        assertEquals(Result.err(new GroupFailure.GroupNotFound()), replace(ALICE, UUID.randomUUID(), "Renamed", 0));
        assertEquals(0, store.writes());
    }

    @Test
    void administratorReplacesMetadataAndIncrementsTheVersion() {
        GroupMetadata described = new GroupMetadata(new GroupName("Trip"), Optional.of(new GroupDescription("Old")));
        UUID group = store.seedGroup(described, 3, Map.of(ALICE, GroupRole.ADMIN));

        GroupDetails updated = assertOk(replace(ALICE, group, " Summer trip ", 3));

        assertEquals(new GroupMetadata(new GroupName("Summer trip"), Optional.empty()), updated.metadata());
        assertEquals(4, updated.version());
        assertEquals(4, store.stored(group).orElseThrow().version());
        assertEquals(List.of("isKnown", "lock", "role", "replace"), store.calls());
        assertEquals(List.of(TransactionDecision.COMMIT), transactions.decisions());
    }

    @Test
    void membersWhoAreNotAdministratorsCannotReplaceMetadata() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER));

        assertEquals(Result.err(new GroupFailure.AdministratorRequired()), replace(BOB, group, "Renamed", 0));
        assertUnchanged(group, TRIP, 0);
    }

    @Test
    void roleIsCheckedBeforeTheVersion() {
        UUID group = store.seedGroup(TRIP, 5, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER));

        assertEquals(Result.err(new GroupFailure.AdministratorRequired()), replace(BOB, group, "Renamed", 0));
        assertEquals(Result.err(new GroupFailure.VersionConflict()), replace(ALICE, group, "Renamed", 4));
        assertUnchanged(group, TRIP, 5);
    }

    @Test
    void membershipIsReadOnlyAfterTheGroupIsLocked() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER));

        replace(BOB, group, "Renamed", 0);

        List<String> calls = store.calls();
        assertTrue(calls.indexOf("lock") >= 0 && calls.indexOf("lock") < calls.indexOf("role"), calls.toString());
    }

    @Test
    void invalidReplacementMetadataIsRejectedBeforeAnyDatabaseWork() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN));
        int callsBefore = store.calls().size();

        Result<GroupDetails, GroupFailure> result = replace(ALICE, group, "x".repeat(65), 0);

        assertEquals(Result.err(new GroupFailure.InvalidMetadata(List.of(
            new MetadataViolation(MetadataViolation.Field.NAME, MetadataViolation.Reason.TOO_LONG)))), result);
        assertEquals(callsBefore, store.calls().size());
        assertEquals(List.of(), transactions.decisions());
    }

    private Result<GroupDetails, GroupFailure> create(UUID caller, CreateGroupCommand command) {
        return withUser(caller, () -> dispatcher.dispatch(GroupCreationService.class, service -> service.create(command)));
    }

    private Result<List<GroupSummary>, GroupFailure> list(UUID caller) {
        return withUser(caller, () -> dispatcher.dispatch(GroupQueryService.class, GroupQueryService::listMine));
    }

    private Result<GroupDetails, GroupFailure> get(UUID caller, UUID groupId) {
        return withUser(caller, () -> dispatcher.dispatch(GroupQueryService.class, service -> service.get(groupId)));
    }

    private Result<GroupDetails, GroupFailure> replace(UUID caller, UUID groupId, String name, long expectedVersion) {
        ReplaceGroupMetadataCommand command = new ReplaceGroupMetadataCommand(groupId, name, null, expectedVersion);
        return withUser(caller, () -> dispatcher.dispatch(GroupMetadataService.class, service -> service.replace(command)));
    }

    private void assertNoDatabaseWork() {
        assertEquals(List.of(), store.calls());
        assertEquals(List.of(), transactions.decisions());
    }

    private void assertUnchanged(UUID group, GroupMetadata metadata, long version) {
        InMemoryGroups.StoredGroup stored = store.stored(group).orElseThrow();
        assertEquals(metadata, stored.metadata());
        assertEquals(version, stored.version());
        assertEquals(0, store.writes());
        assertTrue(transactions.decisions().stream().allMatch(TransactionDecision.ROLLBACK::equals));
    }

    private static <T> T assertOk(Result<T, GroupFailure> result) {
        return switch (result) {
            case Result.Ok<T, GroupFailure>(T value) -> value;
            case Result.Err<T, GroupFailure>(GroupFailure failure) -> throw new AssertionError("expected success: " + failure);
        };
    }
}

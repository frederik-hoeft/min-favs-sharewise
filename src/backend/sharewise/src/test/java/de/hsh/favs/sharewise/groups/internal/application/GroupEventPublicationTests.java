package de.hsh.favs.sharewise.groups.internal.application;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.application.RecordingGroupEvents.Added;
import de.hsh.favs.sharewise.groups.internal.application.RecordingGroupEvents.Created;
import de.hsh.favs.sharewise.groups.internal.application.RecordingGroupEvents.Removed;
import de.hsh.favs.sharewise.groups.internal.application.group.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreateInvitationCommand;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.invitation.GroupInvitationService;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemption;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipService;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeCommand;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeStatus;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * DLV-T02: the Group services publish exactly the facts of the specification's operation table,
 * inside their segment, after the last write, with the revision returned by the membership port
 * and the affected user's ID; every other path publishes nothing.
 */
@ComponentTest
@ResourceLock(GroupApplicationTestConfiguration.FIXTURE_LOCK)
@SpringJUnitConfig({OperationScopeTestComponents.class, IdentityTestConfiguration.class,
    GroupApplicationTestConfiguration.class})
class GroupEventPublicationTests {

    private static final UUID ALICE = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be401");
    private static final UUID BOB = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be402");
    private static final UUID CAROL = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be403");
    private static final UUID UNKNOWN = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be404");
    private static final GroupMetadata TRIP = new GroupMetadata(new GroupName("Trip"), Optional.empty());

    @Autowired OperationDispatcher dispatcher;
    @Autowired InMemoryGroups store;
    @Autowired InMemoryInvitations invitations;
    @Autowired RecordingGroupEvents events;
    @Autowired RecordingTransactions.Log transactions;

    @BeforeEach
    void reset() {
        store.reset();
        invitations.reset();
        events.reset();
        transactions.reset();
        store.provision(ALICE);
        store.provision(BOB);
        store.provision(CAROL);
        store.clearCalls();
    }

    @Test
    void creationPublishesTheGroupAndItsOwnerAtTheInitialRevisionAfterTheInsert() {
        GroupDetails created = ok(create(ALICE, "Trip"));

        assertEquals(List.of(new Created(created.id()), new Added(created.id(), ALICE, 1)), events.facts());
        assertEquals(1, store.membershipRevision(created.id()));
        assertEquals(List.of("isKnown", "create", "publish:groupCreated", "publish:memberAdded"), store.calls());
    }

    @Test
    void rejectedCreationPublishesNothing() {
        assertTrue(create(ALICE, " ").isFailure());
        assertEquals(Result.err(new GroupFailure.UnknownUser()), create(UNKNOWN, "Trip"));

        assertEquals(List.of(), events.facts());
    }

    @Test
    void joiningPublishesTheJoinerAtTheAdvancedRevisionAndOtherRedemptionsPublishNothing() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN));
        CreatedInvitation link = invitation(group, null);
        CreatedInvitation single = invitation(group, 1);

        assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, redeem(ALICE, link).outcome());
        assertEquals(InvitationRedemption.Outcome.JOINED, redeem(BOB, link).outcome());
        assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, redeem(BOB, link).outcome());
        assertEquals(InvitationRedemption.Outcome.JOINED, redeem(CAROL, single).outcome());
        assertEquals(Result.err(new GroupFailure.InvitationUnavailable()), call(BOB, GroupInvitationService.class,
            service -> service.redeem(single.token().value())));
        assertEquals(Result.err(new GroupFailure.InvitationUnavailable()), call(BOB, GroupInvitationService.class,
            service -> service.redeem("broken")));

        assertEquals(List.of(new Added(group, BOB, 2), new Added(group, CAROL, 3)), events.facts());
        assertEquals(3, store.membershipRevision(group));
    }

    @Test
    void leavingAndRemovalPublishTheRemovedUserAtTheAdvancedRevision() {
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER,
            CAROL, GroupRole.MEMBER));
        GroupWorkspace roster = ok(workspace(ALICE, group));

        assertEquals(Result.ok(MembershipChangeStatus.COMPLETED),
            change(BOB, group, memberId(roster, BOB), new MembershipChange.Remove()));
        store.clearCalls();
        assertEquals(Result.ok(MembershipChangeStatus.COMPLETED),
            change(ALICE, group, memberId(roster, CAROL), new MembershipChange.Remove()));

        assertEquals(List.of(new Removed(group, BOB, 2), new Removed(group, CAROL, 3)), events.facts());
        assertEquals(List.of("lock", "changeContext", "remove", "publish:memberRemoved"), store.calls());
        assertEquals(3, store.membershipRevision(group));
    }

    @Test
    void roleChangesAndRejectedMembershipChangesPublishNothing() {
        UUID alone = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN));
        UUID group = store.seedGroup(TRIP, 0, Map.of(ALICE, GroupRole.ADMIN, BOB, GroupRole.MEMBER));
        GroupWorkspace roster = ok(workspace(ALICE, group));
        UUID alice = memberId(roster, ALICE);
        UUID bob = memberId(roster, BOB);

        assertEquals(Result.err(new GroupFailure.LastMember()),
            change(ALICE, alone, ok(workspace(ALICE, alone)).callerMembershipId(), new MembershipChange.Remove()));
        assertEquals(Result.err(new GroupFailure.AdministratorRequired()),
            change(BOB, group, alice, new MembershipChange.Remove()));
        assertEquals(Result.err(new GroupFailure.MemberNotFound()),
            change(ALICE, group, UUID.randomUUID(), new MembershipChange.Remove()));
        assertEquals(Result.err(new GroupFailure.LastAdministrator()),
            change(ALICE, group, alice, new MembershipChange.Remove()));
        assertEquals(Result.err(new GroupFailure.SelfRoleChange()),
            change(ALICE, group, alice, new MembershipChange.SetRole(GroupRole.MEMBER)));
        assertEquals(Result.err(new GroupFailure.GroupNotFound()),
            change(CAROL, group, bob, new MembershipChange.Remove()));
        assertTrue(change(ALICE, group, bob, new MembershipChange.SetRole(GroupRole.MEMBER)).isSuccess());
        assertTrue(change(ALICE, group, bob, new MembershipChange.SetRole(GroupRole.ADMIN)).isSuccess());
        assertTrue(change(BOB, group, alice, new MembershipChange.SetRole(GroupRole.MEMBER)).isSuccess());

        assertEquals(List.of(), events.facts());
        assertEquals(1, store.membershipRevision(group));
        assertEquals(1, store.membershipRevision(alone));
    }

    private Result<GroupDetails, GroupFailure> create(UUID caller, String name) {
        return call(caller, GroupCreationService.class, service -> service.create(new CreateGroupCommand(name, null)));
    }

    private CreatedInvitation invitation(UUID group, Integer limit) {
        return ok(call(ALICE, GroupInvitationService.class,
            service -> service.create(new CreateInvitationCommand(group, null, limit))));
    }

    private InvitationRedemption redeem(UUID user, CreatedInvitation link) {
        return ok(call(user, GroupInvitationService.class, service -> service.redeem(link.token().value())));
    }

    private Result<GroupWorkspace, GroupFailure> workspace(UUID user, UUID group) {
        return call(user, GroupQueryService.class, service -> service.workspace(group));
    }

    private Result<MembershipChangeStatus, GroupFailure> change(UUID user, UUID group, UUID target,
            MembershipChange change) {
        return call(user, GroupMembershipService.class,
            service -> service.change(new MembershipChangeCommand(group, target, change)));
    }

    private <S, T> T call(UUID user, Class<S> service, Function<S, T> action) {
        return withUser(user, () -> dispatcher.dispatch(service, action));
    }

    private static UUID memberId(GroupWorkspace workspace, UUID user) {
        return workspace.members().stream().filter(member -> member.userId().equals(user)).findFirst()
            .orElseThrow().id();
    }

    private static <T> T ok(Result<T, GroupFailure> result) {
        return result.fold(value -> value, failure -> {
            throw new AssertionError("expected success: " + failure);
        });
    }
}

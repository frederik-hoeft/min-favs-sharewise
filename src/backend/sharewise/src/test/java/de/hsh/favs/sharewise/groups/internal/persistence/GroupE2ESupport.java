package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.group.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMetadataService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.group.ReplaceGroupMetadataCommand;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreateInvitationCommand;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.invitation.GroupInvitationService;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemption;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipService;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeCommand;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeStatus;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared stateless scenario operations for the Group application E2E suites. */
final class GroupE2ESupport {

    static final UUID ALICE = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be301");
    static final UUID BOB = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be302");

    private GroupE2ESupport() {
    }

    static Result<GroupDetails, GroupFailure> create(IntegrationTestContext fixture, UUID caller, String name) {
        return withUser(caller, () -> dispatcher(fixture).dispatch(GroupCreationService.class,
            service -> service.create(new CreateGroupCommand(name, null))));
    }

    static Result<GroupDetails, GroupFailure> replace(
            IntegrationTestContext fixture, UUID caller, UUID group, String name, long expectedVersion) {
        ReplaceGroupMetadataCommand command = new ReplaceGroupMetadataCommand(group, name, null, expectedVersion);
        return withUser(caller, () -> dispatcher(fixture).dispatch(GroupMetadataService.class,
            service -> service.replace(command)));
    }

    static GroupWorkspace workspace(IntegrationTestContext fixture, UUID caller, UUID group) {
        return ok(withUser(caller, () -> dispatcher(fixture).dispatch(GroupQueryService.class,
            service -> service.workspace(group))));
    }

    static Result<MembershipChangeStatus, GroupFailure> change(IntegrationTestContext fixture, UUID caller,
            UUID group, UUID target, MembershipChange change) {
        return withUser(caller, () -> dispatcher(fixture).dispatch(GroupMembershipService.class,
            service -> service.change(new MembershipChangeCommand(group, target, change))));
    }

    /** Creates an invitation as {@link #ALICE}, who must administer the group. */
    static CreatedInvitation invitation(IntegrationTestContext fixture, UUID group, Instant expires, Integer limit) {
        return ok(withUser(ALICE, () -> dispatcher(fixture).dispatch(GroupInvitationService.class,
            service -> service.create(new CreateInvitationCommand(group, expires, limit)))));
    }

    static Result<InvitationRedemption, GroupFailure> redeem(IntegrationTestContext fixture, UUID user,
            CreatedInvitation link) {
        return redeem(fixture, user, link.token().value());
    }

    static Result<InvitationRedemption, GroupFailure> redeem(IntegrationTestContext fixture, UUID user, String token) {
        return withUser(user, () -> dispatcher(fixture).dispatch(GroupInvitationService.class,
            service -> service.redeem(token)));
    }

    /** The group's committed membership revision. */
    static long membershipRevision(IntegrationTestContext fixture, UUID group) throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT membership_revision FROM groups.groups WHERE id = ?")) {
            statement.setObject(1, group);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("No group " + group);
                }
                return rows.getLong(1);
            }
        }
    }

    static OperationDispatcher dispatcher(IntegrationTestContext fixture) {
        return fixture.context().getBean(OperationDispatcher.class);
    }

    static <T> List<T> query(IntegrationTestContext fixture, String sql, RowReader<T> reader)
            throws SQLException {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<T> values = new ArrayList<>();
            while (rows.next()) {
                values.add(reader.read(rows));
            }
            return values;
        }
    }

    static <T> T ok(Result<T, GroupFailure> result) {
        return switch (result) {
            case Result.Ok<T, GroupFailure>(T value) -> value;
            case Result.Err<T, GroupFailure>(GroupFailure failure) -> throw new AssertionError("expected success: " + failure);
        };
    }

    @FunctionalInterface
    interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }
}

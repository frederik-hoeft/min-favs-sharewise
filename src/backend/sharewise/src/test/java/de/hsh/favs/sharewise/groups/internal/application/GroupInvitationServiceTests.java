package de.hsh.favs.sharewise.groups.internal.application;

import static de.hsh.favs.sharewise.testsupport.identity.IdentityTestFixture.withUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.OperationScopeTestComponents;
import de.hsh.favs.sharewise.testsupport.identity.IdentityTestConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@ComponentTest
@SpringJUnitConfig({OperationScopeTestComponents.class, IdentityTestConfiguration.class,
    GroupApplicationTestConfiguration.class})
class GroupInvitationServiceTests {
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();
    private static final UUID UNKNOWN = UUID.randomUUID();
    @Autowired OperationDispatcher dispatcher;
    @Autowired InMemoryGroups groups;
    @Autowired InMemoryInvitations invitations;
    @Autowired ApplicationTokenGenerator generator;
    private UUID group;

    @BeforeEach void reset() {
        groups.reset();
        invitations.reset();
        groups.provision(ADMIN);
        groups.provision(MEMBER);
        groups.provision(OUTSIDER);
        group = groups.seedGroup(new GroupMetadata(new GroupName("Trip"), Optional.empty()), 7,
            Map.of(ADMIN, GroupRole.ADMIN, MEMBER, GroupRole.MEMBER));
    }

    @Test void administrationRequiresLiveMembershipAndAdministratorRole() {
        CreatedInvitation link = create(null, null);
        assertEquals(Result.err(new GroupFailure.AdministratorRequired()), call(MEMBER, service -> service.list(group)));
        assertEquals(Result.err(new GroupFailure.GroupNotFound()), call(OUTSIDER, service -> service.list(group)));
        assertEquals(Result.err(new GroupFailure.UnknownUser()), call(UNKNOWN, service -> service.list(group)));
        assertEquals(Result.err(new GroupFailure.AdministratorRequired()), call(MEMBER, service ->
            service.create(new CreateInvitationCommand(group, null, null))));
        assertEquals(Result.err(new GroupFailure.AdministratorRequired()), call(MEMBER, service ->
            service.revoke(group, link.invitation().id())));
        assertEquals(Result.err(new GroupFailure.InvitationNotFound()), call(ADMIN, service ->
            service.revoke(group, UUID.randomUUID())));
        assertEquals(List.of(link.invitation()), ok(call(ADMIN, service -> service.list(group))));
        assertFalse(link.toString().contains(link.token().value()));
    }

    @Test void invalidConstraintsFailWithoutIssuingAnInvitation() {
        Result<CreatedInvitation, GroupFailure> result = call(ADMIN, service ->
            service.create(new CreateInvitationCommand(group, invitations.now, 0)));
        GroupFailure.InvalidInvitation invalid = assertInstanceOf(GroupFailure.InvalidInvitation.class,
            ((Result.Err<CreatedInvitation, GroupFailure>) result).error());
        assertEquals(2, invalid.errors().errors().size());
        assertTrue(ok(call(ADMIN, service -> service.list(group))).isEmpty());
        assertTrue(call(ADMIN, service -> service.create(new CreateInvitationCommand(group, Instant.MAX, 1))).isFailure());
    }

    @Test void validExistingMembersDoNotConsumeOrChangeRoleAndNewMembersConsumeExactlyOnce() {
        CreatedInvitation link = create(null, 2);
        assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, redeem(ADMIN, link).outcome());
        assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, redeem(MEMBER, link).outcome());
        assertEquals(InvitationRedemption.Outcome.JOINED, redeem(OUTSIDER, link).outcome());
        assertEquals(InvitationRedemption.Outcome.ALREADY_MEMBER, redeem(OUTSIDER, link).outcome());
        assertEquals(1, ok(call(ADMIN, service -> service.list(group))).getFirst().useCount());
        assertEquals(Map.of(ADMIN, GroupRole.ADMIN, MEMBER, GroupRole.MEMBER, OUTSIDER, GroupRole.MEMBER),
            groups.stored(group).orElseThrow().members());
        assertEquals(7, groups.stored(group).orElseThrow().version());
    }

    @Test void forgedMalformedExpiredExhaustedAndRevokedAreIndistinguishable() {
        CreatedInvitation link = create(invitations.now.plusSeconds(10), 1);
        Result<InvitationRedemption, GroupFailure> unavailable = Result.err(new GroupFailure.InvitationUnavailable());
        assertEquals(unavailable, call(OUTSIDER, service -> service.redeem("broken")));
        assertEquals(unavailable, call(OUTSIDER, service -> service.redeem(generator.generate(link.invitation().id()).value())));
        assertEquals(Result.err(new GroupFailure.UnknownUser()), call(UNKNOWN, service -> service.redeem(link.token().value())));
        invitations.now = link.invitation().expiresAt();
        assertEquals(unavailable, call(OUTSIDER, service -> service.redeem(link.token().value())));
        assertEquals(0, ok(call(ADMIN, service -> service.list(group))).getFirst().useCount());
        CreatedInvitation single = create(null, 1);
        assertEquals(InvitationRedemption.Outcome.JOINED, redeem(OUTSIDER, single).outcome());
        assertEquals(unavailable, call(ADMIN, service -> service.redeem(single.token().value())));
        assertEquals(InvitationRevocationStatus.REVOKED, ok(call(ADMIN, service -> service.revoke(group, single.invitation().id()))));
        assertEquals(unavailable, call(OUTSIDER, service -> service.redeem(single.token().value())));
    }

    private CreatedInvitation create(Instant expires, Integer limit) {
        return ok(call(ADMIN, service -> service.create(new CreateInvitationCommand(group, expires, limit))));
    }
    private InvitationRedemption redeem(UUID user, CreatedInvitation link) {
        return ok(call(user, service -> service.redeem(link.token().value())));
    }
    private <T> T call(UUID user, Function<GroupInvitationService, T> action) {
        return withUser(user, () -> dispatcher.dispatch(GroupInvitationService.class, action));
    }
    private static <T> T ok(Result<T, GroupFailure> result) {
        return result.fold(value -> value, failure -> { throw new AssertionError(failure); });
    }
}

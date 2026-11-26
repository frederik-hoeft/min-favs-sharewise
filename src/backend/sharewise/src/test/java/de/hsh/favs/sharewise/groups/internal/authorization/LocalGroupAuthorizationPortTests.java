package de.hsh.favs.sharewise.groups.internal.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import de.hsh.favs.sharewise.groups.GroupAuthorizationVerificationFailure;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.groups.VerifiedGroupMembership;
import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupAccess;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMember;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipLookupUnavailable;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LocalGroupAuthorizationPortTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private final CallerAdmission admission = mock(CallerAdmission.class);
    private final GroupAccess access = mock(GroupAccess.class);
    private final LocalGroupAuthorizationPort port = new LocalGroupAuthorizationPort(admission, access,
        new Transactions() {
            public <T> T execute(Supplier<TransactionOutcome<T>> action) {
                var outcome = action.get();
                assertEquals(TransactionDecision.ABSTAIN, outcome.decision());
                return outcome.result();
            }
            public void markRollbackOnly() { throw new UnsupportedOperationException(); }
            public boolean isRollbackOnly() { throw new UnsupportedOperationException(); }
        }, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void authoritativeLookupReturnsTheObservedRoleAndClassifiesDenials() {
        when(admission.requireAuthenticated()).thenReturn(USER);
        when(access.lockForMember(GROUP, USER)).thenReturn(Result.ok(new GroupAccess.Access(
            new LockedGroup(GROUP, 1), new GroupMember(UUID.randomUUID(), USER, GroupRole.ADMIN, NOW))));
        assertEquals(Result.ok(new VerifiedGroupMembership(USER, GROUP, GroupRole.ADMIN, NOW)), port.verifyCurrent(GROUP));
        when(access.lockForMember(GROUP, USER)).thenReturn(Result.err(new GroupFailure.GroupNotFound()));
        assertEquals(Result.err(GroupAuthorizationVerificationFailure.INELIGIBLE), port.verifyCurrent(GROUP));
        when(access.lockForMember(GROUP, USER)).thenReturn(Result.err(new GroupFailure.UnknownUser()));
        assertEquals(Result.err(GroupAuthorizationVerificationFailure.UNKNOWN_USER), port.verifyCurrent(GROUP));
    }

    @Test
    void adapterClassifiedOutagesAreTypedButProgrammingFailuresPropagate() {
        when(admission.requireAuthenticated()).thenReturn(USER);
        when(access.lockForMember(GROUP, USER)).thenThrow(new GroupMembershipLookupUnavailable(new IllegalStateException("fixture")));
        assertEquals(Result.err(GroupAuthorizationVerificationFailure.UNAVAILABLE), port.verifyCurrent(GROUP));
        doThrow(new IllegalArgumentException("fixture")).when(access).lockForMember(GROUP, USER);
        assertThrows(IllegalArgumentException.class, () -> port.verifyCurrent(GROUP));
    }
}

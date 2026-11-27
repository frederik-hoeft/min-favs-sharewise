package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.groups.GroupAuthorizationPort;
import de.hsh.favs.sharewise.groups.GroupAuthorizationVerificationFailure;
import de.hsh.favs.sharewise.groups.VerifiedGroupMembership;
import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupAccess;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipLookupUnavailable;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** The one authoritative membership lookup for issuance and synchronous consumers. */
@Component
@Scope("operation")
final class LocalGroupAuthorizationPort implements GroupAuthorizationPort {
    private static final Logger LOG = LoggerFactory.getLogger(LocalGroupAuthorizationPort.class);
    private final CallerAdmission admission;
    private final GroupAccess access;
    private final Transactions transactions;
    private final Clock clock;

    LocalGroupAuthorizationPort(CallerAdmission admission, GroupAccess access, Transactions transactions,
            @Qualifier("groupAuthorizationClock") Clock clock) {
        this.admission = Objects.requireNonNull(admission);
        this.access = Objects.requireNonNull(access);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Result<VerifiedGroupMembership, GroupAuthorizationVerificationFailure> verifyCurrent(UUID groupId) {
        UUID userId = admission.requireAuthenticated();
        Objects.requireNonNull(groupId, "groupId");
        // Transactions rejects nested or unrelated active segments. The role read follows the root lock.
        return transactions.execute(() -> {
            try {
                return TransactionOutcome.abstain(access.lockForMember(groupId, userId)
                    .map(current -> new VerifiedGroupMembership(userId, groupId, current.caller().role(), clock.instant()))
                    .mapError(LocalGroupAuthorizationPort::failure));
            } catch (GroupMembershipLookupUnavailable outage) {
                LOG.warn("Group membership lookup unavailable");
                return TransactionOutcome.abstain(Result.err(GroupAuthorizationVerificationFailure.UNAVAILABLE));
            }
        });
    }

    private static GroupAuthorizationVerificationFailure failure(GroupFailure failure) {
        return switch (failure) {
            case GroupFailure.UnknownUser ignored -> GroupAuthorizationVerificationFailure.UNKNOWN_USER;
            case GroupFailure.GroupNotFound ignored -> GroupAuthorizationVerificationFailure.INELIGIBLE;
            default -> throw new IllegalStateException("Unexpected authoritative membership outcome");
        };
    }
}

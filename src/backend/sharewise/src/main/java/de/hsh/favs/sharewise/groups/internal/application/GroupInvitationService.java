package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.infra.security.ApplicationToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator.GeneratedToken;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenParser;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationError;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Owns the invitation lifecycle; credentials never authorize administration. */
@Component
@Scope("operation")
public class GroupInvitationService {
    // Keep the HTTP timestamp within a portable ISO calendar range and PostgreSQL's supported range.
    private static final Instant MAX_EXPIRY = Instant.parse("9999-12-31T23:59:59.999999Z");

    private final CallerAdmission admission;
    private final GroupAccess access;
    private final GroupMutationPort groups;
    private final GroupMembershipPort members;
    private final GroupInvitationPort invitations;
    private final ApplicationTokenGenerator generator;
    private final ApplicationTokenParser parser;
    private final Transactions transactions;

    GroupInvitationService(CallerAdmission admission, GroupAccess access, GroupMutationPort groups,
            GroupMembershipPort members, GroupInvitationPort invitations, ApplicationTokenGenerator generator,
            ApplicationTokenParser parser, Transactions transactions) {
        this.admission = Objects.requireNonNull(admission);
        this.access = Objects.requireNonNull(access);
        this.groups = Objects.requireNonNull(groups);
        this.members = Objects.requireNonNull(members);
        this.invitations = Objects.requireNonNull(invitations);
        this.generator = Objects.requireNonNull(generator);
        this.parser = Objects.requireNonNull(parser);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public Result<List<InvitationSummary>, GroupFailure> list(UUID groupId) {
        admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.read(asAdministrator(groupId,
            group -> Result.ok(List.copyOf(invitations.list(group))))));
    }

    public Result<CreatedInvitation, GroupFailure> create(CreateInvitationCommand command) {
        Objects.requireNonNull(command);
        admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.mutation(asAdministrator(command.groupId(),
            group -> createLocked(group, command))));
    }

    public Result<InvitationRevocationStatus, GroupFailure> revoke(UUID groupId, UUID selector) {
        Objects.requireNonNull(selector);
        admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.mutation(asAdministrator(groupId, group ->
            invitations.revoke(group, selector) ? Result.ok(InvitationRevocationStatus.REVOKED)
                : Result.err(new GroupFailure.InvitationNotFound()))));
    }

    public Result<InvitationRedemption, GroupFailure> redeem(String bearer) {
        admission.requireAuthenticated();
        Optional<ApplicationToken> presented = parser.parse(bearer);
        return transactions.execute(() -> GroupOutcomes.mutation(admission.admit().flatMap(caller ->
            presented.<Result<InvitationRedemption, GroupFailure>>map(token -> redeem(caller.userId(), token))
                .orElseGet(GroupInvitationService::unavailable))));
    }

    private <T> Result<T, GroupFailure> asAdministrator(UUID groupId,
            Function<LockedGroup, Result<T, GroupFailure>> action) {
        Objects.requireNonNull(groupId);
        return admission.admit().flatMap(caller -> access.lockForMember(groupId, caller.userId()))
            .flatMap(GroupAccess.Access::requireAdministrator).flatMap(locked -> action.apply(locked.group()));
    }

    private Result<CreatedInvitation, GroupFailure> createLocked(LockedGroup group, CreateInvitationCommand command) {
        Instant now = invitations.currentTime();
        // PostgreSQL timestamptz has microsecond precision. Validate the exact persisted value.
        Instant expiry = command.expiresAt() == null ? null : command.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Integer limit = command.maxUses();
        List<ValidationError> errors = new ArrayList<>();
        if (expiry != null && (!expiry.isAfter(now) || expiry.isAfter(MAX_EXPIRY))) {
            errors.add(new ValidationError("expiresAt", "invalid", "Select a future expiration within the supported calendar range."));
        }
        if (limit != null && limit < 1) {
            errors.add(new ValidationError("maxUses", "invalid", "The maximum number of uses must be positive."));
        }
        if (!errors.isEmpty()) {
            return Result.err(new GroupFailure.InvalidInvitation(new ValidationErrors(errors)));
        }
        GeneratedToken token = generator.generate(invitations.newInvitationId());
        InvitationSummary created = invitations.insert(group, token.selector(), token.verifierHash(), now, expiry, limit);
        return Result.ok(new CreatedInvitation(created, token));
    }

    private Result<InvitationRedemption, GroupFailure> redeem(UUID userId, ApplicationToken token) {
        // Do not trust the preliminary lookup: revocation may have committed while we waited.
        Optional<LockedGroup> locked = invitations.groupIdForSelector(token.selector()).flatMap(groups::lockGroup);
        if (locked.isEmpty()) {
            return unavailable();
        }
        LockedGroup group = locked.get();
        Optional<StoredInvitation> stored = invitations.find(group, token.selector());
        if (stored.isEmpty() || !stored.get().matches(token)
                || !stored.get().summary().availableAt(invitations.currentTime())) {
            return unavailable();
        }
        if (members.memberForUser(group, userId).isPresent()) {
            return Result.ok(new InvitationRedemption(group.id(), InvitationRedemption.Outcome.ALREADY_MEMBER));
        }
        members.addMember(group, userId);
        invitations.consume(group, token.selector());
        return Result.ok(new InvitationRedemption(group.id(), InvitationRedemption.Outcome.JOINED));
    }

    private static <T> Result<T, GroupFailure> unavailable() {
        return Result.err(new GroupFailure.InvitationUnavailable());
    }
}

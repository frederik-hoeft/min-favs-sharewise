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
    private final GroupMembershipPort members;
    private final GroupInvitationPort invitations;
    private final ApplicationTokenGenerator generator;
    private final ApplicationTokenParser parser;
    private final Transactions transactions;

    GroupInvitationService(CallerAdmission admission, GroupAccess access,
            GroupMembershipPort members, GroupInvitationPort invitations, ApplicationTokenGenerator generator,
            ApplicationTokenParser parser, Transactions transactions) {
        this.admission = Objects.requireNonNull(admission);
        this.access = Objects.requireNonNull(access);
        this.members = Objects.requireNonNull(members);
        this.invitations = Objects.requireNonNull(invitations);
        this.generator = Objects.requireNonNull(generator);
        this.parser = Objects.requireNonNull(parser);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public Result<List<InvitationSummary>, GroupFailure> list(UUID groupId) {
        UUID userId = admission.requireAuthenticated();
        Objects.requireNonNull(groupId);
        return transactions.execute(() -> GroupOutcomes.read(admission.resolveProjection(
            invitations.listForMember(groupId, userId), new GroupFailure.GroupNotFound())
            .flatMap(listing -> listing.callerRole().isAdministrator() ? Result.ok(listing.invitations())
                : Result.err(new GroupFailure.AdministratorRequired()))));
    }

    public Result<CreatedInvitation, GroupFailure> create(CreateInvitationCommand command) {
        Objects.requireNonNull(command);
        UUID userId = admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.mutation(asAdministrator(command.groupId(), userId,
            group -> createLocked(group, command))));
    }

    public Result<InvitationRevocationStatus, GroupFailure> revoke(UUID groupId, UUID selector) {
        Objects.requireNonNull(selector);
        UUID userId = admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.mutation(asAdministrator(groupId, userId, group ->
            invitations.revoke(group, selector) ? Result.ok(InvitationRevocationStatus.REVOKED)
                : Result.err(new GroupFailure.InvitationNotFound()))));
    }

    public Result<InvitationPreview, GroupFailure> preview(String bearer) {
        UUID userId = admission.requireAuthenticated();
        Optional<ApplicationToken> presented = parser.parse(bearer);
        return transactions.execute(() -> GroupOutcomes.read(presented
            .map(token -> admission.resolveProjection(invitations.previewContext(token.selector(), userId),
                new GroupFailure.InvitationUnavailable()).flatMap(context ->
                    valid(context.invitation(), context.evaluatedAt(), token)
                        ? Result.ok(new InvitationPreview(context.groupName())) : unavailable()))
            .orElseGet(() -> admission.admit().flatMap(caller -> unavailable()))));
    }

    public Result<InvitationRedemption, GroupFailure> redeem(String bearer) {
        UUID userId = admission.requireAuthenticated();
        Optional<ApplicationToken> presented = parser.parse(bearer);
        return transactions.execute(() -> GroupOutcomes.mutation(presented
            .map(token -> redeem(userId, token))
            .orElseGet(() -> admission.admit().flatMap(caller -> unavailable()))));
    }

    private <T> Result<T, GroupFailure> asAdministrator(UUID groupId, UUID userId,
            Function<LockedGroup, Result<T, GroupFailure>> action) {
        Objects.requireNonNull(groupId);
        return access.lockForMember(groupId, userId)
            .flatMap(GroupAccess.Access::requireAdministrator).flatMap(locked -> action.apply(locked.group()));
    }

    private Result<CreatedInvitation, GroupFailure> createLocked(LockedGroup group, CreateInvitationCommand command) {
        InvitationIssuanceMaterial material = invitations.issuanceMaterial(group);
        Instant now = material.createdAt();
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
        GeneratedToken token = generator.generate(material.selector());
        InvitationSummary created = invitations.insert(group, token.selector(), token.verifierHash(), now, expiry, limit);
        return Result.ok(new CreatedInvitation(created, token));
    }

    private Result<InvitationRedemption, GroupFailure> redeem(UUID userId, ApplicationToken token) {
        // The selector locates the root, never authorizes redemption. Re-read after the lock wait.
        return admission.resolveProjection(invitations.lockGroupForRedemption(token.selector(), userId),
            new GroupFailure.InvitationUnavailable()).flatMap(group -> redeemLocked(group, userId, token));
    }

    private Result<InvitationRedemption, GroupFailure> redeemLocked(LockedGroup group, UUID userId, ApplicationToken token) {
        Optional<InvitationRedemptionContext> current = invitations.redemptionContext(group, token.selector(), userId);
        if (current.isEmpty()) {
            return unavailable();
        }
        InvitationRedemptionContext context = current.get();
        if (!valid(context.invitation(), context.evaluatedAt(), token)) {
            return unavailable();
        }
        if (context.alreadyMember()) {
            return Result.ok(new InvitationRedemption(group.id(), InvitationRedemption.Outcome.ALREADY_MEMBER));
        }
        members.addMember(group, userId);
        invitations.consume(group, token.selector());
        return Result.ok(new InvitationRedemption(group.id(), InvitationRedemption.Outcome.JOINED));
    }

    private static boolean valid(StoredInvitation invitation, Instant evaluatedAt, ApplicationToken token) {
        return invitation.matches(token) && invitation.summary().availableAt(evaluatedAt);
    }

    private static <T> Result<T, GroupFailure> unavailable() {
        return Result.err(new GroupFailure.InvitationUnavailable());
    }
}

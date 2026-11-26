package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.GroupOutcomes;
import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.application.access.LockedGroup;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupAccess;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Replaces a group's editable metadata on behalf of a current administrator.
 *
 * <p>Follows the group mutation protocol: the group row is locked before the caller's role and
 * the expected version are checked, so the decision uses the latest committed membership state.
 */
@Component
@Scope("operation")
public class GroupMetadataService {

    private final CallerAdmission admission;
    private final GroupMutationPort groups;
    private final GroupAccess access;
    private final Transactions transactions;

    GroupMetadataService(CallerAdmission admission, GroupMutationPort groups, GroupAccess access, Transactions transactions) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.access = Objects.requireNonNull(access, "access");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Result<GroupDetails, GroupFailure> replace(ReplaceGroupMetadataCommand command) {
        Objects.requireNonNull(command, "command");
        UUID userId = admission.requireAuthenticated();
        Result<GroupMetadata, List<MetadataViolation>> metadata = GroupMetadata.parse(command.name(), command.description());
        return switch (metadata) {
            case Result.Err<GroupMetadata, List<MetadataViolation>>(List<MetadataViolation> violations) ->
                Result.err(new GroupFailure.InvalidMetadata(violations));
            case Result.Ok<GroupMetadata, List<MetadataViolation>>(GroupMetadata valid) -> transactions.execute(() ->
                GroupOutcomes.mutation(replaceLocked(command, valid, userId)));
        };
    }

    private Result<GroupDetails, GroupFailure> replaceLocked(
            ReplaceGroupMetadataCommand command, GroupMetadata metadata, UUID userId) {
        return access.lockForMember(command.groupId(), userId)
            .flatMap(GroupAccess.Access::requireAdministrator)
            .flatMap(authorized -> {
                LockedGroup group = authorized.group();
                if (group.version() != command.expectedVersion()) {
                    return Result.err(new GroupFailure.VersionConflict());
                }
                return Result.ok(groups.replaceMetadata(group, userId, metadata));
            });
    }
}

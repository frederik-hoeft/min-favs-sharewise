package de.hsh.favs.sharewise.groups.internal.application.group;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.GroupOutcomes;
import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Creates a group whose only member and administrator is the authenticated caller. */
@Component
@Scope("operation")
public class GroupCreationService {

    private final CallerAdmission admission;
    private final GroupCreationPort groups;
    private final Transactions transactions;

    GroupCreationService(CallerAdmission admission, GroupCreationPort groups, Transactions transactions) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Result<GroupDetails, GroupFailure> create(CreateGroupCommand command) {
        Objects.requireNonNull(command, "command");
        admission.requireAuthenticated();
        Result<GroupMetadata, List<MetadataViolation>> metadata = GroupMetadata.parse(command.name(), command.description());
        return switch (metadata) {
            case Result.Err<GroupMetadata, List<MetadataViolation>>(List<MetadataViolation> violations) ->
                Result.err(new GroupFailure.InvalidMetadata(violations));
            case Result.Ok<GroupMetadata, List<MetadataViolation>>(GroupMetadata valid) -> transactions.execute(() ->
                GroupOutcomes.mutation(admission.admit()
                    .map(caller -> groups.createWithAdministrator(caller.userId(), valid).details())));
        };
    }
}

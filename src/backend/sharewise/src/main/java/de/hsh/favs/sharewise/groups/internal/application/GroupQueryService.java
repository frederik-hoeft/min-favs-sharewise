package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Member-scoped group reads for the authenticated caller. */
@Component
@Scope("operation")
public class GroupQueryService {

    private final CallerAdmission admission;
    private final GroupReadPort groups;
    private final Transactions transactions;

    GroupQueryService(CallerAdmission admission, GroupReadPort groups, Transactions transactions) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** The groups in which the caller is a member. */
    public Result<List<GroupSummary>, GroupFailure> listMine() {
        UUID userId = admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.read(
            admission.resolveProjection(groups.listForMember(userId), new GroupFailure.UnknownUser())
                .map(List::copyOf)));
    }

    /** The group if the caller is a member; a missing group and a non-member are indistinguishable. */
    public Result<GroupDetails, GroupFailure> get(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        UUID userId = admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.read(
            admission.resolveProjection(groups.findForMember(groupId, userId), new GroupFailure.GroupNotFound())));
    }

    public Result<GroupWorkspace, GroupFailure> workspace(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        UUID userId = admission.requireAuthenticated();
        return transactions.execute(() -> GroupOutcomes.read(
            admission.resolveProjection(groups.findWorkspace(groupId, userId), new GroupFailure.GroupNotFound())));
    }
}

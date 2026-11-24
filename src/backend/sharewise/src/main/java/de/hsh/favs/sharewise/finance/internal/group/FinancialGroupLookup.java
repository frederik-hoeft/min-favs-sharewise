package de.hsh.favs.sharewise.finance.internal.group;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable lookup result, independent of the lookup's locking strategy.
 * Readiness is initialization, not authorization, writability or actor eligibility.
 */
public record FinancialGroupLookup(Optional<FinancialGroup> group) {

    public FinancialGroupLookup {
        Objects.requireNonNull(group, "group");
    }

    /**
     * Shared business-operation precondition, evaluated after scoped authorization.
     * A missing or provisioning root is not ready regardless of its revision.
     * This check neither establishes nor extends a transaction or lock.
     */
    public Result<FinancialGroup, FinancialGroupFailure> requireReady() {
        return group.<Result<FinancialGroup, FinancialGroupFailure>>map(root -> switch (root.state()) {
            case PROVISIONING -> Result.err(FinancialGroupFailure.NOT_READY);
            case OPEN, CLOSED, ARCHIVED -> Result.ok(root);
        }).orElseGet(() -> Result.err(FinancialGroupFailure.NOT_READY));
    }
}

package de.hsh.favs.sharewise.finance.internal.projection;

import java.util.Objects;
import java.util.UUID;

/**
 * Trusted Group-originated fact that a group was created, so its Finance root may leave provisioning.
 *
 * @param groupId the upstream group identity, also the Finance root identity
 */
public record ActivateFinancialGroup(UUID groupId) {

    public ActivateFinancialGroup {
        Objects.requireNonNull(groupId, "groupId");
    }
}

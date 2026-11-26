package de.hsh.favs.sharewise.groups.internal.persistence;

import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipLookupUnavailable;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.SqlAvailability;
import java.util.function.Supplier;

/** Preserves authoritative read failure semantics across root, member and caller projections. */
final class GroupMembershipRead {
    private GroupMembershipRead() { }

    static <T> T execute(Supplier<T> read) {
        try {
            return read.get();
        } catch (org.jooq.exception.DataAccessException | org.springframework.dao.DataAccessException failure) {
            if (SqlAvailability.isUnavailable(failure)) {
                throw new GroupMembershipLookupUnavailable(failure);
            }
            throw failure;
        }
    }
}

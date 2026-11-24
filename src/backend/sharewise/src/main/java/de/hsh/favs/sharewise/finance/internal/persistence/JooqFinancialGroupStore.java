package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Groups.GROUPS;

import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupLookup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.enums.GroupState;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

@Repository
@Scope("operation")
class JooqFinancialGroupStore implements FinancialGroupStore {

    private final JooqAccess database;

    JooqFinancialGroupStore(JooqAccess database) {
        this.database = database;
    }

    @Override
    public FinancialGroup ensureAndLock(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        DSLContext db = database.dsl();
        db.insertInto(GROUPS).columns(GROUPS.ID).values(groupId)
                .onConflict(GROUPS.ID).doNothing().execute();
        // A separate statement observes the committed winner of a concurrent insert
        // under READ COMMITTED without updating the existing row merely to lock it.
        return lock(db, groupId).orElseThrow(() ->
                new IllegalStateException("Ensured Finance group is missing: " + groupId));
    }

    @Override
    public FinancialGroupLookup lockExisting(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return new FinancialGroupLookup(lock(database.dsl(), groupId));
    }

    private static Optional<FinancialGroup> lock(DSLContext db, UUID groupId) {
        return db.select(GROUPS.ID, GROUPS.STATE, GROUPS.CURRENT_REVISION)
                .from(GROUPS).where(GROUPS.ID.eq(groupId)).forUpdate()
                .fetchOptional(row -> new FinancialGroup(row.value1(), state(row.value2()), row.value3()));
    }

    private static FinancialGroupState state(GroupState state) {
        return switch (state) {
            case provisioning -> FinancialGroupState.PROVISIONING;
            case open -> FinancialGroupState.OPEN;
            case closed -> FinancialGroupState.CLOSED;
            case archived -> FinancialGroupState.ARCHIVED;
        };
    }
}

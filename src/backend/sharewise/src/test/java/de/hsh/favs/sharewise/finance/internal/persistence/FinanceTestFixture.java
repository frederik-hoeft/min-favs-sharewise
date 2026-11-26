package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Actors.ACTORS;
import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Groups.GROUPS;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroup;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.enums.GroupState;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.ActorsRecord;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.GroupsRecord;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresLockWaits;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * Test-only committed Finance state for E2E tests. Seeds commit on, and reads observe, an
 * independent application-role connection, never the pooled connections of the operations under
 * test. Seeds bypass the projection operations to reach states they cannot produce, such as a
 * nonzero financial revision or a closed root.
 */
public final class FinanceTestFixture {

    /** A fixed past creation and update time for seeded rows, distinguishable from {@code now()}. */
    public static final OffsetDateTime SEEDED_AT = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private FinanceTestFixture() {
    }

    /** All committed Finance roots and actors, ordered by key, for whole-state comparisons. */
    public record CommittedState(List<GroupsRecord> roots, List<ActorsRecord> actors) {
    }

    public static void seedRoot(IntegrationTestContext fixture, UUID id, FinancialGroupState state, long revision)
            throws SQLException {
        Objects.requireNonNull(fixture, "fixture");
        IntegrationTestContext.seedCommitted(fixture.database(), connection -> db(connection).insertInto(GROUPS)
                .set(GROUPS.ID, Objects.requireNonNull(id, "id"))
                .set(GROUPS.STATE, toDatabase(state))
                .set(GROUPS.CURRENT_REVISION, revision)
                .set(GROUPS.CREATED_AT, SEEDED_AT)
                .execute());
    }

    /** Seeds an actor of an existing root with {@link #SEEDED_AT} as creation and update time. */
    public static void seedActor(IntegrationTestContext fixture, FinancialActor actor) throws SQLException {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(actor, "actor");
        IntegrationTestContext.seedCommitted(fixture.database(), connection -> db(connection).insertInto(ACTORS)
                .set(ACTORS.GROUP_ID, actor.groupId())
                .set(ACTORS.USER_ID, actor.userId())
                .set(ACTORS.DELETED, actor.state() == MembershipState.REMOVED)
                .set(ACTORS.UPSTREAM_REVISION, actor.upstreamRevision())
                .set(ACTORS.CREATED_AT, SEEDED_AT)
                .set(ACTORS.UPDATED_AT, SEEDED_AT)
                .execute());
    }

    public static Optional<FinancialGroup> root(IntegrationTestContext fixture, UUID id) throws SQLException {
        return rootRow(fixture, id).map(row -> new FinancialGroup(row.getId(), toDomain(row.getState()),
                row.getCurrentRevision()));
    }

    public static Optional<GroupsRecord> rootRow(IntegrationTestContext fixture, UUID id) throws SQLException {
        try (Connection connection = fixture.connectAsApplication()) {
            return db(connection).selectFrom(GROUPS).where(GROUPS.ID.eq(id)).fetchOptional();
        }
    }

    /** The group's committed actors, ordered by user ID. */
    public static List<FinancialActor> actors(IntegrationTestContext fixture, UUID groupId) throws SQLException {
        try (Connection connection = fixture.connectAsApplication()) {
            return db(connection).selectFrom(ACTORS).where(ACTORS.GROUP_ID.eq(groupId)).orderBy(ACTORS.USER_ID)
                    .fetch(FinanceTestFixture::toDomain);
        }
    }

    public static ActorsRecord actorRow(IntegrationTestContext fixture, UUID groupId, UUID userId)
            throws SQLException {
        try (Connection connection = fixture.connectAsApplication()) {
            return db(connection).selectFrom(ACTORS)
                    .where(ACTORS.GROUP_ID.eq(groupId).and(ACTORS.USER_ID.eq(userId))).fetchSingle();
        }
    }

    public static CommittedState committedState(IntegrationTestContext fixture) throws SQLException {
        try (Connection connection = fixture.connectAsApplication()) {
            DSLContext db = db(connection);
            return new CommittedState(db.selectFrom(GROUPS).orderBy(GROUPS.ID).fetch(),
                    db.selectFrom(ACTORS).orderBy(ACTORS.GROUP_ID, ACTORS.USER_ID).fetch());
        }
    }

    /** Holds an existing root's lock on an independent connection until the caller ends its transaction. */
    public static Connection lockRoot(IntegrationTestContext fixture, UUID id) throws SQLException {
        return PostgresLockWaits.holdLocks(fixture, "SELECT id FROM finance.groups WHERE id = ? FOR UPDATE", id);
    }

    private static DSLContext db(Connection connection) {
        return DSL.using(connection, SQLDialect.POSTGRES);
    }

    private static FinancialActor toDomain(ActorsRecord row) {
        return new FinancialActor(row.getGroupId(), row.getUserId(),
                row.getDeleted() ? MembershipState.REMOVED : MembershipState.ACTIVE, row.getUpstreamRevision());
    }

    private static FinancialGroupState toDomain(GroupState state) {
        return switch (state) {
            case provisioning -> FinancialGroupState.PROVISIONING;
            case open -> FinancialGroupState.OPEN;
            case closed -> FinancialGroupState.CLOSED;
            case archived -> FinancialGroupState.ARCHIVED;
        };
    }

    private static GroupState toDatabase(FinancialGroupState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case PROVISIONING -> GroupState.provisioning;
            case OPEN -> GroupState.open;
            case CLOSED -> GroupState.closed;
            case ARCHIVED -> GroupState.archived;
        };
    }
}

package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Actors.ACTORS;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.FinancialActorStore;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * jOOQ adapter of {@link FinancialActorStore}. As a {@code @Repository}, its argument and state exceptions
 * reach callers translated by Spring as {@code InvalidDataAccessApiUsageException}; the port
 * documents the caller-visible contract.
 */
@Repository
@Scope("operation")
class JooqFinancialActorStore implements FinancialActorStore {

    private final JooqAccess database;

    JooqFinancialActorStore(JooqAccess database) {
        this.database = database;
    }

    @Override
    public Optional<FinancialActor> find(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        return database.dsl().select(ACTORS.DELETED, ACTORS.UPSTREAM_REVISION)
                .from(ACTORS).where(ACTORS.GROUP_ID.eq(groupId).and(ACTORS.USER_ID.eq(userId)))
                .fetchOptional(row -> new FinancialActor(groupId, userId, state(row.value1()), row.value2()));
    }

    @Override
    public void insert(FinancialActor actor) {
        Objects.requireNonNull(actor, "actor");
        // Timestamps come from the column defaults; a duplicate key or missing root fails.
        database.dsl().insertInto(ACTORS)
                .columns(ACTORS.GROUP_ID, ACTORS.USER_ID, ACTORS.DELETED, ACTORS.UPSTREAM_REVISION)
                .values(actor.groupId(), actor.userId(), deleted(actor.state()), actor.upstreamRevision())
                .execute();
    }

    @Override
    public void supersede(FinancialActor current, FinancialActor next) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(next, "next");
        if (!current.sameIdentity(next)) {
            throw new IllegalArgumentException("Superseding actor must have the same identity");
        }
        if (next.upstreamRevision() <= current.upstreamRevision()) {
            throw new IllegalArgumentException("Superseding actor must have a greater revision");
        }
        // Compare-and-set on the previously read state: under the caller's root lock a mismatch is
        // impossible, so one is a protocol violation rather than an expected outcome. A match does
        // not prove that the caller holds the root lock.
        int updated = database.dsl().update(ACTORS)
                .set(ACTORS.DELETED, deleted(next.state()))
                .set(ACTORS.UPSTREAM_REVISION, next.upstreamRevision())
                .where(ACTORS.GROUP_ID.eq(current.groupId())
                        .and(ACTORS.USER_ID.eq(current.userId()))
                        .and(ACTORS.UPSTREAM_REVISION.eq(current.upstreamRevision()))
                        .and(ACTORS.DELETED.eq(deleted(current.state()))))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("Finance actor changed concurrently or is missing: group "
                    + current.groupId() + ", user " + current.userId());
        }
    }

    private static boolean deleted(MembershipState state) {
        return switch (state) {
            case ACTIVE -> false;
            case REMOVED -> true;
        };
    }

    private static MembershipState state(boolean deleted) {
        return deleted ? MembershipState.REMOVED : MembershipState.ACTIVE;
    }
}

package de.hsh.favs.sharewise.finance.internal.persistence;

import static de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.Actors.ACTORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import de.hsh.favs.sharewise.finance.internal.actor.FinancialActorStore;
import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.group.FinancialGroupStore;
import de.hsh.favs.sharewise.finance.internal.persistence.generated.tables.records.ActorsRecord;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestScope;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/** Actor port mapping and fail-closed write semantics within one rollback-only segment. */
@Tag("integration")
class JooqFinancialActorStoreIT extends IntegrationTestBase {

    @Test
    void insertsAndFindsBothMembershipStatesPerGroup() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> inSegment(scope, db -> {
            UUID group = ensureRoot(scope);
            UUID otherGroup = ensureRoot(scope);
            UUID active = UUID.randomUUID();
            UUID removed = UUID.randomUUID();
            assertEquals(Optional.empty(), actors.find(group, active));
            FinancialActor activeActor = new FinancialActor(group, active, MembershipState.ACTIVE, 1);
            FinancialActor tombstone = new FinancialActor(group, removed, MembershipState.REMOVED, Long.MAX_VALUE);
            actors.insert(activeActor);
            actors.insert(tombstone);
            assertEquals(Optional.of(activeActor), actors.find(group, active));
            assertEquals(Optional.of(tombstone), actors.find(group, removed));
            assertEquals(Optional.empty(), actors.find(otherGroup, active), "Actor identity is group-local");
            assertEquals(false, row(db, group, active).getDeleted());
            assertEquals(true, row(db, group, removed).getDeleted());
            assertEquals(Long.MAX_VALUE, row(db, group, removed).getUpstreamRevision());
        }));
    }

    @Test
    void supersedeReplacesStateAndRevisionButPreservesCreation() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> inSegment(scope, db -> {
            UUID group = ensureRoot(scope);
            UUID user = UUID.randomUUID();
            FinancialActor first = new FinancialActor(group, user, MembershipState.ACTIVE, 1);
            actors.insert(first);
            ActorsRecord inserted = row(db, group, user);
            FinancialActor removed = new FinancialActor(group, user, MembershipState.REMOVED, 2);
            actors.supersede(first, removed);
            assertEquals(Optional.of(removed), actors.find(group, user));
            FinancialActor rejoined = new FinancialActor(group, user, MembershipState.ACTIVE, Long.MAX_VALUE);
            actors.supersede(removed, rejoined);
            assertEquals(Optional.of(rejoined), actors.find(group, user));
            assertEquals(inserted.getCreatedAt(), row(db, group, user).getCreatedAt());
            assertEquals(1, db.fetchCount(ACTORS, ACTORS.GROUP_ID.eq(group)));
        }));
    }

    @Test
    void supersedeFailsClosedWhenTheStoredActorDiffersFromTheExpectedSnapshot() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> inSegment(scope, db -> {
            UUID group = ensureRoot(scope);
            UUID user = UUID.randomUUID();
            FinancialActor stored = new FinancialActor(group, user, MembershipState.ACTIVE, 3);
            actors.insert(stored);
            FinancialActor next = new FinancialActor(group, user, MembershipState.REMOVED, 5);
            assertTranslated(IllegalStateException.class,
                    () -> actors.supersede(new FinancialActor(group, user, MembershipState.ACTIVE, 2), next));
            assertTranslated(IllegalStateException.class,
                    () -> actors.supersede(new FinancialActor(group, user, MembershipState.REMOVED, 3), next));
            UUID unknown = UUID.randomUUID();
            assertTranslated(IllegalStateException.class, () -> actors.supersede(
                    new FinancialActor(group, unknown, MembershipState.ACTIVE, 1),
                    new FinancialActor(group, unknown, MembershipState.REMOVED, 2)));
            assertEquals(Optional.of(stored), actors.find(group, user));
            assertEquals(Optional.empty(), actors.find(group, unknown));
        }));
    }

    @Test
    void supersedeRejectsAnotherIdentityOrANonIncreasingRevisionBeforeWriting() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> inSegment(scope, db -> {
            UUID group = ensureRoot(scope);
            UUID user = UUID.randomUUID();
            FinancialActor stored = new FinancialActor(group, user, MembershipState.ACTIVE, 3);
            actors.insert(stored);
            assertTranslated(IllegalArgumentException.class, () -> actors.supersede(stored,
                    new FinancialActor(group, UUID.randomUUID(), MembershipState.REMOVED, 4)));
            assertTranslated(IllegalArgumentException.class,
                    () -> actors.supersede(stored, new FinancialActor(group, user, MembershipState.REMOVED, 3)));
            assertTranslated(IllegalArgumentException.class,
                    () -> actors.supersede(stored, new FinancialActor(group, user, MembershipState.REMOVED, 2)));
            assertEquals(Optional.of(stored), actors.find(group, user));
        }));
    }

    @Test
    void insertOfAnExistingActorSurfacesTheKeyViolation() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> inSegment(scope, db -> {
            UUID group = ensureRoot(scope);
            UUID user = UUID.randomUUID();
            actors.insert(new FinancialActor(group, user, MembershipState.ACTIVE, 1));
            // The key violation aborts the PostgreSQL transaction, so it must be the last statement.
            assertThrows(DuplicateKeyException.class,
                    () -> actors.insert(new FinancialActor(group, user, MembershipState.ACTIVE, 2)));
        }));
    }

    @Test
    void insertWithoutAFinanceRootSurfacesTheForeignKeyViolation() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> inSegment(scope, db ->
                assertThrows(DataIntegrityViolationException.class, () -> actors.insert(
                        new FinancialActor(UUID.randomUUID(), UUID.randomUUID(), MembershipState.REMOVED, 1)))));
    }

    @Test
    void rejectsNullArgumentsAndRequiresAnActiveLogicalSegment() {
        runServiceTest(FinancialActorStore.class, (actors, scope) -> {
            UUID group = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            FinancialActor actor = new FinancialActor(group, user, MembershipState.ACTIVE, 1);
            FinancialActor next = new FinancialActor(group, user, MembershipState.REMOVED, 2);
            assertNoActiveSegment(() -> actors.find(group, user));
            assertNoActiveSegment(() -> actors.insert(actor));
            assertNoActiveSegment(() -> actors.supersede(actor, next));
            inSegment(scope, db -> {
                assertThrows(NullPointerException.class, () -> actors.find(null, user));
                assertThrows(NullPointerException.class, () -> actors.find(group, null));
                assertThrows(NullPointerException.class, () -> actors.insert(null));
                assertThrows(NullPointerException.class, () -> actors.supersede(null, next));
                assertThrows(NullPointerException.class, () -> actors.supersede(actor, null));
                assertTrue(actors.find(group, user).isEmpty());
            });
        });
    }

    private static void inSegment(IntegrationTestScope scope, Consumer<DSLContext> work) {
        scope.resolve(Transactions.class).execute(() -> {
            work.accept(scope.resolve(JooqAccess.class).dsl());
            return TransactionOutcome.abstain(null);
        });
    }

    private static UUID ensureRoot(IntegrationTestScope scope) {
        UUID id = UUID.randomUUID();
        scope.resolve(FinancialGroupStore.class).ensureAndLock(id);
        return id;
    }

    private static ActorsRecord row(DSLContext db, UUID group, UUID user) {
        return db.selectFrom(ACTORS).where(ACTORS.GROUP_ID.eq(group).and(ACTORS.USER_ID.eq(user))).fetchSingle();
    }

    /** The real {@code @Repository} proxy translates argument and state exceptions, keeping the original cause. */
    private static void assertTranslated(Class<? extends RuntimeException> expected, Executable operation) {
        InvalidDataAccessApiUsageException failure = assertThrows(InvalidDataAccessApiUsageException.class, operation);
        assertInstanceOf(expected, failure.getCause());
    }

    private static void assertNoActiveSegment(Executable operation) {
        InvalidDataAccessApiUsageException failure = assertThrows(InvalidDataAccessApiUsageException.class, operation);
        assertEquals("No active logical transaction segment",
                assertInstanceOf(IllegalStateException.class, failure.getCause()).getMessage());
    }
}

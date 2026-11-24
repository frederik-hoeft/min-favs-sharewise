package de.hsh.favs.sharewise.identity.internal.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionDecision;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Profile policy and caller isolation without an external database. */
class AccountProfileServiceTests {

    private static final UUID ALICE = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID BOB = UUID.fromString("12345678-1234-4123-8123-1234567890ac");

    @Test
    void missingProfileAndExplicitClearRemainNullAndAreScopedToAuthenticatedCaller() {
        Store store = new Store();
        store.accounts.put(ALICE, new AccountProfile(ALICE, "Alice@Example.org", null));
        store.accounts.put(BOB, new AccountProfile(BOB, "bob@example.org", "Bob"));
        RecordingTransactions tx = new RecordingTransactions();
        AccountProfileService service = service(Optional.of(ALICE), tx, store);
        assertEquals(Result.ok(new AccountProfile(ALICE, "Alice@Example.org", null)), service.get());
        assertEquals(TransactionDecision.ABSTAIN, tx.decision);
        assertEquals(Result.ok(new AccountProfile(ALICE, "Alice@Example.org", "🌍 Alice")),
                service.setDisplayName(update("🌍 Alice")));
        assertEquals(TransactionDecision.COMMIT, tx.decision);
        assertEquals(Result.ok(new AccountProfile(ALICE, "Alice@Example.org", null)),
                service.setDisplayName(update(null)));
        assertEquals(Result.ok(new AccountProfile(ALICE, "Alice@Example.org", null)), service.get());
        assertEquals("Bob", store.accounts.get(BOB).displayName());
        assertEquals(ALICE, store.lastUpdated);
    }

    @Test
    void missingOrDisabledAccountIsDeniedWithoutCommittingOrInserting() {
        Store store = new Store();
        RecordingTransactions tx = new RecordingTransactions();
        AccountProfileService service = service(Optional.of(ALICE), tx, store);
        assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE), service.get());
        assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE),
                service.setDisplayName(update("Any name")));
        assertEquals(TransactionDecision.ABSTAIN, tx.decision);
        assertEquals(0, store.writes);
        store.accounts.put(ALICE, new AccountProfile(ALICE, "a@example.org", null));
        store.disabled = true;
        assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE), service.get());
        assertEquals(Result.err(AccountProfileFailure.ACCOUNT_UNAVAILABLE),
                service.setDisplayName(update(null)));
        assertEquals(0, store.writes);
    }

    @Test
    void anonymousCallsDoNotTouchDatabase() {
        Store store = new Store();
        RecordingTransactions tx = new RecordingTransactions();
        AccountProfileService service = service(Optional.empty(), tx, store);
        assertThrows(UnauthenticatedCallerException.class, service::get);
        assertThrows(UnauthenticatedCallerException.class, () -> service.setDisplayName(update("Alice")));
        assertEquals(0, tx.executions);
    }

    @Test
    void displayNameValidatesScalarsControlCharactersAndExplicitNullWithoutNormalizing() {
        assertNull(update(null).value());
        assertEquals(" Å  ", update(" Å  ").value());
        assertEquals("🌍".repeat(80), update("🌍".repeat(80)).value());
        for (String invalid : new String[]{"", "  ", "x".repeat(81), "🌍".repeat(81),
                "Alice\nBob", "Alice\u0085Bob", "Alice\u2028Bob", "Alice\u2029Bob", "\u2003",
                "\uD83D", "\uDF0D", "\uD83Dx", "x\uDF0D"}) {
            Result<DisplayNameUpdate, ?> result = DisplayNameUpdate.tryFrom(invalid);
            assertFalse(result.isSuccess(), () -> "Expected denial for " + invalid.codePoints().count());
        }
        assertTrue(DisplayNameUpdate.tryFrom("<script>alert(1)</script>").isSuccess(),
                "Markup-like names are data, not HTML and must be escaped by clients");
    }

    private static DisplayNameUpdate update(String value) {
        return ((Result.Ok<DisplayNameUpdate, ?>) DisplayNameUpdate.tryFrom(value)).value();
    }

    private static AccountProfileService service(Optional<UUID> userId, Transactions tx, Store store) {
        IdentityContext identity = new IdentityContext() {
            @Override public Optional<UUID> userId() { return userId; }
            @Override public Optional<UUID> sessionId() { return userId; }
        };
        return new AccountProfileService(identity, tx, store);
    }

    private static final class RecordingTransactions implements Transactions {
        int executions;
        TransactionDecision decision;

        @Override
        public <T> T execute(Supplier<TransactionOutcome<T>> operation) {
            executions++;
            TransactionOutcome<T> outcome = operation.get();
            decision = outcome.decision();
            return outcome.result();
        }

        @Override
        public void markRollbackOnly() { throw new UnsupportedOperationException(); }
        @Override
        public boolean isRollbackOnly() { return false; }
    }

    private static final class Store implements AccountProfileStore {
        final Map<UUID, AccountProfile> accounts = new HashMap<>();
        boolean disabled;
        int writes;
        UUID lastUpdated;

        @Override
        public Optional<AccountProfile> findActive(UUID userId) {
            return disabled ? Optional.empty() : Optional.ofNullable(accounts.get(userId));
        }

        @Override
        public Optional<AccountProfile> setDisplayNameIfActive(UUID userId, String name) {
            Optional<AccountProfile> account = findActive(userId);
            if (account.isPresent()) {
                writes++;
                lastUpdated = userId;
                AccountProfile old = account.orElseThrow();
                AccountProfile updated = new AccountProfile(userId, old.email(), name);
                accounts.put(userId, updated);
                return Optional.of(updated);
            }
            return Optional.empty();
        }
    }
}

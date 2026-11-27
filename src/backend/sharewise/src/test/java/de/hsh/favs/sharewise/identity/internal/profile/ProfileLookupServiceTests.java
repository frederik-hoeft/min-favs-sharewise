package de.hsh.favs.sharewise.identity.internal.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileLookupServiceTests {
    @Test
    void emptyLookupRequiresIdentityButDoesNotOpenTransactionOrQueryStore() {
        IdentityContext identity = mock(IdentityContext.class);
        when(identity.requireUserId()).thenReturn(UUID.randomUUID());
        Transactions transactions = mock(Transactions.class);
        AccountProfileStore profiles = mock(AccountProfileStore.class);
        ProfileLookup command = ProfileLookup.tryFrom(List.of()).fold(value -> value, errors -> { throw new AssertionError(errors); });
        assertEquals(List.of(), new ProfileLookupService(identity, transactions, profiles).lookup(command));
        verifyNoInteractions(transactions, profiles);
    }
}

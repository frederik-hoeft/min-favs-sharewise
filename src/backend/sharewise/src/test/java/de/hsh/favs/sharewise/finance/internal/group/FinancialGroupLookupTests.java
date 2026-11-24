package de.hsh.favs.sharewise.finance.internal.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialGroupLookupTests {

    @Test
    void rejectsNullOptional() {
        assertThrows(NullPointerException.class, () -> new FinancialGroupLookup(null));
    }

    @Test
    void absentRootIsNotReady() {
        FinancialGroupLookup lookup = new FinancialGroupLookup(Optional.empty());
        assertEquals(Optional.empty(), lookup.group());
        assertEquals(Result.err(FinancialGroupFailure.NOT_READY), lookup.requireReady());
    }

    @Test
    void provisioningIsNotReadyRegardlessOfRevision() {
        for (long revision : List.of(0L, Long.MAX_VALUE)) {
            FinancialGroup root = new FinancialGroup(UUID.randomUUID(), FinancialGroupState.PROVISIONING, revision);
            FinancialGroupLookup lookup = new FinancialGroupLookup(Optional.of(root));
            assertEquals(Optional.of(root), lookup.group());
            assertEquals(Result.err(FinancialGroupFailure.NOT_READY), lookup.requireReady());
        }
    }

    @Test
    void activatedRootIsReadyRegardlessOfLifecycleOrRevision() {
        for (FinancialGroupState state : List.of(FinancialGroupState.OPEN, FinancialGroupState.CLOSED, FinancialGroupState.ARCHIVED)) {
            for (long revision : List.of(0L, Long.MAX_VALUE)) {
                FinancialGroup root = new FinancialGroup(UUID.randomUUID(), state, revision);
                FinancialGroupLookup lookup = new FinancialGroupLookup(Optional.of(root));
                assertEquals(Result.ok(root), lookup.requireReady());
            }
        }
    }
}

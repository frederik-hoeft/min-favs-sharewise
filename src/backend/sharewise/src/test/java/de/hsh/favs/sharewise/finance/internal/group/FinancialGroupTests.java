package de.hsh.favs.sharewise.finance.internal.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialGroupTests {

    @Test
    void snapshotRequiresIdentityLifecycleAndNonnegativeRevision() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> new FinancialGroup(null, FinancialGroupState.PROVISIONING, 0));
        assertThrows(NullPointerException.class, () -> new FinancialGroup(id, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FinancialGroup(id, FinancialGroupState.OPEN, -1));
        assertEquals(0L, new FinancialGroup(id, FinancialGroupState.PROVISIONING, 0).currentRevision());
        assertEquals(Long.MAX_VALUE,
                new FinancialGroup(id, FinancialGroupState.ARCHIVED, Long.MAX_VALUE).currentRevision());
    }
}

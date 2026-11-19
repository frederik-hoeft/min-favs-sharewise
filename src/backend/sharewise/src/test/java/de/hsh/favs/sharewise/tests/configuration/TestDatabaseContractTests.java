package de.hsh.favs.sharewise.tests.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.database.TestDatabase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import org.junit.jupiter.api.Test;

class TestDatabaseContractTests {

    @Test
    void applicationFixturesExposeTheDatabaseContractRatherThanItsVendorImplementation()
            throws NoSuchMethodException {
        assertTrue(TestDatabase.class.isAssignableFrom(PostgresTestDatabase.class));
        assertEquals(TestDatabase.class, IntegrationTestContext.class.getMethod("database").getReturnType());
    }
}

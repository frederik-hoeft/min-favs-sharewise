package de.hsh.favs.sharewise.testsupport.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owned, disposable test database. Application-facing tests depend on this contract;
 * PostgreSQL bootstrap and migration details remain in its implementation.
 */
public interface TestDatabase extends AutoCloseable {

    String databaseName();

    String jdbcUrl();

    Connection connectAsApplication() throws SQLException;

    Connection connectAsMigrator() throws SQLException;

    Connection connectAsAdmin() throws SQLException;

    /** Server-level connection, not connected to the disposable test database. */
    Connection connectToServerAsAdmin() throws SQLException;

    @Override
    void close();
}

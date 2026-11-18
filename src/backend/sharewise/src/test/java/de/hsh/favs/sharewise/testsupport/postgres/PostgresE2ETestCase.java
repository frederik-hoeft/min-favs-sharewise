package de.hsh.favs.sharewise.testsupport.postgres;

import de.hsh.favs.sharewise.ShareWiseApplication;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.ConfigurableApplicationContext;

/** Per-test ownership of a disposable migrated database, production context, and committed writes. */
public abstract class PostgresE2ETestCase {

    private PostgresApplicationFixture fixture;

    protected final void start(Class<?>... configurations) {
        startWithSchema(new String[0], configurations);
    }

    protected final void startWithSchema(String statement, Class<?>... configurations) {
        startWithSchema(new String[]{statement}, configurations);
    }

    private void startWithSchema(String[] schemaStatements, Class<?>... configurations) {
        if (fixture != null) {
            throw new IllegalStateException("E2E fixture already initialized");
        }
        Class<?>[] applicationSources = new Class<?>[configurations.length + 1];
        applicationSources[0] = ShareWiseApplication.class;
        System.arraycopy(configurations, 0, applicationSources, 1, configurations.length);
        fixture = PostgresApplicationFixture.start(
            PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> {
                if (schemaStatements.length != 0) {
                    try (Connection migrator = database.connectAsMigrator();
                         Statement statement = migrator.createStatement()) {
                        for (String ddl : schemaStatements) {
                            statement.execute(ddl);
                        }
                    }
                }
            }, applicationSources);
    }

    protected final ConfigurableApplicationContext context() {
        if (fixture == null) {
            throw new IllegalStateException("E2E fixture not initialized");
        }
        return fixture.context();
    }

    protected final PostgresApplicationFixture fixture() {
        if (fixture == null) {
            throw new IllegalStateException("E2E fixture not initialized");
        }
        return fixture;
    }

    @AfterEach
    final void disposeDatabase() {
        if (fixture != null) {
            try {
                fixture.close();
            } finally {
                fixture = null;
            }
        }
    }
}

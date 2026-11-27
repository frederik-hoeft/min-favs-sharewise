package de.hsh.favs.sharewise.testsupport.configuration;

/**
 * Shared properties for full-application component tests that run without a database.
 *
 * <p>The exclusions remove every database-backed auto-configuration, including the Spring
 * Modulith event publication registry. Tests combine further exclusions or properties by
 * concatenation, for example {@code EXCLUDE_DATABASE_AUTOCONFIGURATION + ",com.example.Other"}.
 */
public final class DatabaseFreeContext {

    /** {@code spring.autoconfigure.exclude} entry for all database-backed auto-configurations. */
    public static final String EXCLUDE_DATABASE_AUTOCONFIGURATION = "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
            + "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration,"
            + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration,"
            + "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration";

    private DatabaseFreeContext() {
    }
}

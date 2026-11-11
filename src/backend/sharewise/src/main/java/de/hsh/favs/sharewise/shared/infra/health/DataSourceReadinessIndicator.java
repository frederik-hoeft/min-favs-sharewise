package de.hsh.favs.sharewise.shared.infra.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies that the configured relational {@link DataSource} can provide a valid connection.
 */
@Component
public final class DataSourceReadinessIndicator implements ReadinessIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceReadinessIndicator.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public DataSourceReadinessIndicator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public boolean isReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException exception) {
            LOG.warn("Database readiness check failed", exception);
            return false;
        }
    }
}

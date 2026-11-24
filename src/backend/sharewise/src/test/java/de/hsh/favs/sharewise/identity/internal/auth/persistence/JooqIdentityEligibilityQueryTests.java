package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.identity.internal.verification.EligibilityQueryUnavailableException;
import java.sql.SQLException;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

/** Exercises adapter failure classification independently of the PostgreSQL behavior suite. */
class JooqIdentityEligibilityQueryTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"08006", "57014", "55P03", "57P01", "57P02", "57P03"})
    void recognizedJdbcFailuresBecomeUnavailable(String state) throws SQLException {
        SQLException sql = new SQLException("test outage", state);
        try (MockConnection connection = new MockConnection(context -> { throw sql; })) {
            JooqIdentityEligibilityQuery query = new JooqIdentityEligibilityQuery(
                    () -> DSL.using(connection, SQLDialect.POSTGRES));
            EligibilityQueryUnavailableException failure = assertThrows(EligibilityQueryUnavailableException.class,
                    () -> query.findSessionValidityWindow(USER, SESSION));
            assertSame(sql, failure.getCause().getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"42601", "42P01", "42501", "23503", "XX000"})
    void unexpectedSqlErrorsAreNotAvailabilityFailures(String state) throws SQLException {
        SQLException sql = new SQLException("test defect", state);
        try (MockConnection connection = new MockConnection(context -> { throw sql; })) {
            JooqIdentityEligibilityQuery query = new JooqIdentityEligibilityQuery(
                    () -> DSL.using(connection, SQLDialect.POSTGRES));
            org.jooq.exception.DataAccessException failure = assertThrows(org.jooq.exception.DataAccessException.class,
                    () -> query.findSessionValidityWindow(USER, SESSION));
            assertEquals(state, failure.sqlState());
        }
    }

    @Test
    void translatedSpringExceptionPreservesOriginalCause() throws SQLException {
        DataAccessResourceFailureException translated = new DataAccessResourceFailureException("test outage",
                new SQLException("connection broken", "08006"));
        try (MockConnection connection = new MockConnection(context -> { throw translated; })) {
            JooqIdentityEligibilityQuery query = new JooqIdentityEligibilityQuery(
                    () -> DSL.using(connection, SQLDialect.POSTGRES));
            EligibilityQueryUnavailableException failure = assertThrows(EligibilityQueryUnavailableException.class,
                    () -> query.findSessionValidityWindow(USER, SESSION));
            assertSame(translated, failure.getCause());
        }
    }
}

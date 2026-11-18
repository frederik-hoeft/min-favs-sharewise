package de.hsh.favs.sharewise.testsupport.transactions;

import java.util.List;
import org.springframework.jdbc.core.RowMapper;

/**
 * Materialized JDBC reads of SQL already visible in the enclosing rollback-test transaction.
 * The inspector cannot start/finalize a transaction or expose a JDBC connection. It never
 * flushes a JPA persistence context; callers must flush explicitly inside their logical segment.
 */
public interface PendingStateInspector {

    <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments);

    <T> T queryForObject(String sql, Class<T> resultType, Object... arguments);
}

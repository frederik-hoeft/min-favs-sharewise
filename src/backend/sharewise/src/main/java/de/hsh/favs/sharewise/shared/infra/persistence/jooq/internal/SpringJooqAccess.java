package de.hsh.favs.sharewise.shared.infra.persistence.jooq.internal;

import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import java.sql.Connection;
import java.util.Objects;
import org.jooq.Configuration;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Adds segment lifetime checks to Spring Boot's transaction-aware jOOQ configuration. */
@Component
@Scope("operation")
final class SpringJooqAccess implements JooqAccess {

    private final TransactionSegmentAccess segments;
    private final DSLContext rootDsl;

    SpringJooqAccess(TransactionSegmentAccess segments, DSLContext rootDsl) {
        this.segments = Objects.requireNonNull(segments);
        this.rootDsl = Objects.requireNonNull(rootDsl);
    }

    @Override
    public DSLContext dsl() {
        TransactionSegmentAccess.SegmentLease lease = segments.captureCurrentSegment();
        // Preserve Spring Boot's transaction-aware physical provider, but guard
        // connection acquisition even when callers access the derived configuration.
        ConnectionProvider delegate = rootDsl.configuration().connectionProvider();
        ConnectionProvider checked = new ConnectionProvider() {
            @Override
            public Connection acquire() {
                lease.requireCurrent();
                return delegate.acquire();
            }

            @Override
            public void release(Connection connection) {
                // Cleanup must remain possible after an execution failure.
                delegate.release(connection);
            }
        };
        // The listener also guards executions that reuse an already prepared
        // statement. Append it instead of replacing Boot's existing listeners.
        Configuration configuration = rootDsl.configuration().derive(checked).deriveAppending(
            new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
                @Override
                public void start(ExecuteContext context) {
                    lease.requireCurrent();
                }
            }));
        return configuration.dsl();
    }
}

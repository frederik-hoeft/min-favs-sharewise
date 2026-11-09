package de.hsh.favs.sharewise.shared.infra.persistence.jooq;

import java.util.function.Function;
import org.jooq.DSLContext;

/**
 * Gives persistence adapters a jOOQ context bound to the currently executing
 * physical transaction segment. This is infrastructure, not a business port.
 */
public interface JooqAccess {

    /**
     * Execute work with a segment-bound context. Calling this outside a segment
     * fails; executable queries constructed through the supplied context also
     * check their originating segment before SQL execution.
     *
     * Do not retain the context, its configuration, cursors, streams, JDBC
     * handles, or derived queries beyond this callback. In particular, do not
     * derive a configuration that discards the installed execution guard.
     */
    <T> T withDsl(Function<DSLContext, T> work);
}

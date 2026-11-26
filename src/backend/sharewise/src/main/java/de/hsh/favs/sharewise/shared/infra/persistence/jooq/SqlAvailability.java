package de.hsh.favs.sharewise.shared.infra.persistence.jooq;

import java.sql.SQLException;

/** Recognizes operational SQL states without reclassifying programming or integrity failures. */
public final class SqlAvailability {
    private SqlAvailability() { }
    public static boolean isUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("57014")
                        || state.equals("55P03") || state.equals("57P01")
                        || state.equals("57P02") || state.equals("57P03"))) {
                    return true;
                }
            }
        }
        return false;
    }
}

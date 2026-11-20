package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Temporary descriptors; replaced by PostgreSQL-generated jOOQ schema types before Gate B. */
final class AuthTables {

    static final Table<org.jooq.Record> USERS = DSL.table(DSL.name("identity", "users"));
    static final Field<UUID> USER_ID = DSL.field(DSL.name("id"), UUID.class);
    static final Field<String> EMAIL = DSL.field(DSL.name("email"), String.class);
    static final Field<String> NORMALIZED_EMAIL = DSL.field(DSL.name("normalized_email"), String.class);
    static final Field<String> PASSWORD_HASH = DSL.field(DSL.name("password_hash"), String.class);
    static final Field<OffsetDateTime> DISABLED_AT = DSL.field(DSL.name("disabled_at"), OffsetDateTime.class);
    static final Field<Integer> FAILED_ATTEMPTS = DSL.field(DSL.name("failed_login_attempts"), Integer.class);
    static final Field<OffsetDateTime> LOCKED_UNTIL = DSL.field(DSL.name("locked_until"), OffsetDateTime.class);
    static final Field<OffsetDateTime> ACCOUNT_CREATED_AT = DSL.field(DSL.name("created_at"), OffsetDateTime.class);

    static final Table<org.jooq.Record> SESSIONS = DSL.table(DSL.name("identity", "sessions"));
    static final Field<OffsetDateTime> SESSION_CREATED_AT = DSL.field(DSL.name("created_at"), OffsetDateTime.class);
    static final Field<UUID> SESSION_ID = DSL.field(DSL.name("id"), UUID.class);
    static final Field<UUID> SESSION_USER_ID = DSL.field(DSL.name("user_id"), UUID.class);
    static final Field<OffsetDateTime> IDLE_EXPIRES_AT = DSL.field(DSL.name("idle_expires_at"), OffsetDateTime.class);
    static final Field<OffsetDateTime> ABSOLUTE_EXPIRES_AT = DSL.field(DSL.name("absolute_expires_at"), OffsetDateTime.class);
    static final Field<OffsetDateTime> REVOKED_AT = DSL.field(DSL.name("revoked_at"), OffsetDateTime.class);

    static final Table<org.jooq.Record> REFRESH_TOKENS = DSL.table(DSL.name("identity", "refresh_tokens"));
    static final Field<UUID> TOKEN_ID = DSL.field(DSL.name("id"), UUID.class);
    static final Field<UUID> TOKEN_SESSION_ID = DSL.field(DSL.name("session_id"), UUID.class);
    static final Field<byte[]> VERIFIER_HASH = DSL.field(DSL.name("verifier_hash"), byte[].class);
    static final Field<OffsetDateTime> ISSUED_AT = DSL.field(DSL.name("issued_at"), OffsetDateTime.class);
    static final Field<OffsetDateTime> CONSUMED_AT = DSL.field(DSL.name("consumed_at"), OffsetDateTime.class);

    private AuthTables() { }

    static OffsetDateTime dbTime(Instant time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

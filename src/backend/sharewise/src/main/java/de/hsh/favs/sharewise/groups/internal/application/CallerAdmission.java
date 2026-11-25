package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Admits the verified IAM caller to Group operations when Group Management knows them locally.
 *
 * <p>Every Group operation passes through this service, which is therefore the single place
 * where later tasks extend local-user resolution (for example an authoritative IAM lookup on a
 * local miss). Admission establishes local existence only, never authorization for a group.
 */
@Component
@Scope("operation")
class CallerAdmission {

    private final IdentityContext identity;
    private final GroupUserRegistry users;

    CallerAdmission(IdentityContext identity, GroupUserRegistry users) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.users = Objects.requireNonNull(users, "users");
    }

    /** Fails with {@code UnauthenticatedCallerException} before any input validation or database work. */
    void requireAuthenticated() {
        identity.requireUserId();
    }

    /** Resolves the caller against the local registry; must run inside the operation's segment. */
    Result<AdmittedCaller, GroupFailure> admit() {
        UUID userId = identity.requireUserId();
        if (!users.isKnown(userId)) {
            return Result.err(new GroupFailure.UnknownUser());
        }
        return Result.ok(new AdmittedCaller(userId));
    }

    /** An authenticated caller registered in {@code groups.users}. */
    record AdmittedCaller(UUID userId) {
        AdmittedCaller {
            Objects.requireNonNull(userId, "userId");
        }
    }
}

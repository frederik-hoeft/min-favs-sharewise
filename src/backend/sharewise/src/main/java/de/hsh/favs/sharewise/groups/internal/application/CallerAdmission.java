package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Admits the verified IAM caller to Group operations when Group Management knows them locally.
 *
 * <p>Successful member-scoped projections already establish local existence through foreign keys.
 * Resolve separately only for creation or a projection miss. Later authoritative IAM resolution
 * belongs here; a resolved miss must retry the relevant projection, not bypass authorization.
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
    UUID requireAuthenticated() {
        return identity.requireUserId();
    }

    /** Resolves the caller against the local registry; must run inside the operation's segment. */
    Result<AdmittedCaller, GroupFailure> admit() {
        UUID userId = identity.requireUserId();
        if (!users.isKnown(userId)) {
            return Result.err(new GroupFailure.UnknownUser());
        }
        return Result.ok(new AdmittedCaller(userId));
    }

    /** Distinguishes a local identity miss from a known caller without the requested resource. */
    <T> Result<T, GroupFailure> resolveProjection(Optional<T> projection, GroupFailure missing) {
        return projection.<Result<T, GroupFailure>>map(Result::ok)
            .orElseGet(() -> admit().flatMap(caller -> Result.err(missing)));
    }

    /** An authenticated caller registered in {@code groups.users}. */
    record AdmittedCaller(UUID userId) {
        AdmittedCaller {
            Objects.requireNonNull(userId, "userId");
        }
    }
}

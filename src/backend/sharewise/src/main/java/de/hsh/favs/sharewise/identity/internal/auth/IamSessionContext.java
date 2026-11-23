package de.hsh.favs.sharewise.identity.internal.auth;

/** IAM-private, operation-scoped verified session evidence for sensitive account operations. */
@FunctionalInterface
public interface IamSessionContext {
    VerifiedIamSession requireSession();
}

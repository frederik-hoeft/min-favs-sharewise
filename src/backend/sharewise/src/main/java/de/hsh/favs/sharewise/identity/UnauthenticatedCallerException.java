package de.hsh.favs.sharewise.identity;

/** Indicates that an operation requiring an authenticated IAM caller has none. */
public final class UnauthenticatedCallerException extends RuntimeException {

    public UnauthenticatedCallerException() {
        super("An authenticated caller is required");
    }
}

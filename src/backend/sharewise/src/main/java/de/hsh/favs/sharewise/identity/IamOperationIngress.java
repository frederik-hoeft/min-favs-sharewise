package de.hsh.favs.sharewise.identity;

import java.util.function.Function;

/**
 * Explicit trusted entry point for non-HTTP IAM operations.
 *
 * <p>An actor ID taken from an event or job is not a credential. A caller must
 * deliberately choose anonymous execution or present a primary IAM access JWT
 * that passes issuer, profile, signature and lifetime verification.
 */
public interface IamOperationIngress {

    /**
     * Dispatches an explicitly anonymous operation.
     *
     * @param entryType operation entry-service type
     * @param action operation action inside the new scope
     * @param <S> operation entry-service type
     * @param <R> operation result type
     * @return the action's result
     */
    <S, R> R dispatchAnonymous(Class<S> entryType, Function<S, R> action);

    /**
     * Verifies an untrusted primary access token before dispatching the operation.
     *
     * @param token untrusted compact bearer JWT
     * @param entryType operation entry-service type
     * @param action operation action inside the new scope
     * @param <S> operation entry-service type
     * @param <R> operation result type
     * @return the action's result
     */
    <S, R> R dispatchBearer(String token, Class<S> entryType, Function<S, R> action);
}

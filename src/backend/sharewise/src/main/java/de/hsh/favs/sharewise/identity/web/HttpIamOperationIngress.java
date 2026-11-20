package de.hsh.favs.sharewise.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;

/**
 * Dispatches an HTTP operation using only evidence established by IAM bearer middleware.
 *
 * <p>Missing middleware is an integration failure, not an anonymous identity.
 */
public interface HttpIamOperationIngress {

    /**
     * Dispatches with the verified or explicitly anonymous ingress for this request.
     *
     * @param request HTTP request previously processed by the IAM security chain
     * @param entryType operation entry-service type
     * @param action operation action inside the new scope
     * @param <S> operation entry-service type
     * @param <R> operation result type
     * @return the action's result
     */
    <S, R> R dispatch(HttpServletRequest request, Class<S> entryType, Function<S, R> action);
}

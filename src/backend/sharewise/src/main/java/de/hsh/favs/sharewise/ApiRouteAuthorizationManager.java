package de.hsh.favs.sharewise;

import de.hsh.favs.sharewise.identity.transport.IamApiSecurityContribution;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.ApiCustomAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * HTTP admission against the actual MVC route table, including controller/method
 * metadata. An unmapped URL remains an MVC 404; a mapped but unclassified API
 * endpoint is denied by default. Domain authorization still happens in operations.
 */
final class ApiRouteAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final IamApiSecurityContribution iam;
    private final Map<String, ApiCustomAccessPolicy> customPolicies;

    ApiRouteAuthorizationManager(ObjectProvider<RequestMappingHandlerMapping> mappings,
            IamApiSecurityContribution iam, List<ApiCustomAccessPolicy> policies) {
        this.mappings = Objects.requireNonNull(mappings);
        this.iam = Objects.requireNonNull(iam);
        Map<String, ApiCustomAccessPolicy> registered = new HashMap<>();
        for (ApiCustomAccessPolicy policy : policies) {
            if (policy.name() == null || policy.name().isBlank()
                    || registered.putIfAbsent(policy.name(), policy) != null) {
                throw new IllegalStateException("Duplicate or empty API access policy name");
            }
        }
        this.customPolicies = Map.copyOf(registered);
    }

    @Override
    public AuthorizationDecision authorize(java.util.function.Supplier<? extends org.springframework.security.core.Authentication> authentication,
            RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        RequestMappingHandlerMapping mapping = mappings.getObject();
        RequestMappingInfo best = null;
        HandlerMethod selected = null;
        boolean ambiguous = false;
        // Spring MVC orders matching mappings by specificity. Reproduce that
        // selection, never whichever HashMap entry happens to be visited first:
        // a permissive wildcard must not override a protected exact route.
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo candidate = entry.getKey().getMatchingCondition(request);
            if (candidate == null) {
                continue;
            }
            int comparison = best == null ? -1 : candidate.compareTo(best, request);
            if (comparison < 0) {
                best = candidate;
                selected = entry.getValue();
                ambiguous = false;
            } else if (comparison == 0) {
                // MVC would fail on an ambiguous mapping. Our security layer
                // must never select the more permissive declaration instead.
                ambiguous = true;
            }
        }
        if (selected == null) {
            // There is no MVC endpoint. Preserve ordinary 404/method handling.
            return new AuthorizationDecision(true);
        }
        if (ambiguous) {
            return new AuthorizationDecision(false);
        }
        ApiAccess access = AnnotatedElementUtils.findMergedAnnotation(selected.getMethod(), ApiAccess.class);
        if (access == null) {
            access = AnnotatedElementUtils.findMergedAnnotation(selected.getBeanType(), ApiAccess.class);
        }
        return new AuthorizationDecision(allowed(access, request));
    }

    private boolean allowed(ApiAccess access, HttpServletRequest request) {
        if (access == null) {
            return false;
        }
        if (access.value() == ApiAccess.Mode.ANONYMOUS) {
            return access.policy().isBlank();
        }
        if (!iam.hasVerifiedIdentity(request)) {
            return false;
        }
        if (access.value() == ApiAccess.Mode.AUTHENTICATED_IAM) {
            return access.policy().isBlank();
        }
        ApiCustomAccessPolicy custom = customPolicies.get(access.policy());
        return custom != null && custom.allows(request);
    }
}

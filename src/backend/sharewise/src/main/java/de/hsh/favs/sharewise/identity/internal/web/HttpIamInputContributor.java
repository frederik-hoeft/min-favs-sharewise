package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.shared.infra.di.OperationInputContributor;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** Contributes the bearer middleware's verified result from the enclosing HTTP request. */
@Component
@RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
public final class HttpIamInputContributor implements OperationInputContributor {

    private final HttpServletRequest request;

    HttpIamInputContributor(HttpServletRequest request) {
        this.request = Objects.requireNonNull(request);
    }

    @Override
    public void contribute(OperationInputRegistry inputs) {
        Object evidence = request.getAttribute(IamBearerIngressFilter.VERIFIED_INGRESS_ATTRIBUTE);
        if (!(evidence instanceof Optional<?> candidate)) {
            throw new IllegalStateException("Trusted IAM HTTP middleware did not process this request");
        }
        candidate.ifPresent(value -> {
            if (!(value instanceof VerifiedJwtCredentialCandidate verified)) {
                throw new IllegalStateException("Invalid IAM middleware evidence type");
            }
            inputs.add(VerifiedJwtCredentialCandidate.class, verified);
        });
    }
}

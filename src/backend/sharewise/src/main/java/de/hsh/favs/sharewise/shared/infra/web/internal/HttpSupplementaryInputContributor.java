package de.hsh.favs.sharewise.shared.infra.web.internal;

import de.hsh.favs.sharewise.shared.infra.claims.SupplementaryCredentialInputs;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputContributor;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** Transfers only trusted, request-local supplementary source proofs. */
@Component
@RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
public final class HttpSupplementaryInputContributor implements OperationInputContributor {
    private final HttpServletRequest request;

    public HttpSupplementaryInputContributor(HttpServletRequest request) { this.request = request; }

    @Override public void contribute(OperationInputRegistry inputs) {
        inputs.add(SupplementaryCredentialInputs.class, SupplementaryCredentialIngressFilter.inputs(request));
    }
}

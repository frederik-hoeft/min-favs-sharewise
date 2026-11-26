package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
import java.util.List;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Publishes only primary JWT evidence that was already verified by the trusted ingress. */
@Component
@Scope("operation")
final class IamPrimaryCredentialProvider implements AuthorizationCredentialProvider {

    private final IamOperationSecurityConfiguration ingress;

    IamPrimaryCredentialProvider(IamOperationSecurityConfiguration ingress) {
        this.ingress = ingress;
    }

    @Override
    public List<VerifiedJwtCredentialCandidate> credentials() {
        return ingress.primary().map(List::of).orElseGet(List::of);
    }
}

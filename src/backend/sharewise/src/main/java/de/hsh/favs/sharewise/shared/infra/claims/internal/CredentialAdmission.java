package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.di.OperationAdmission;
import java.util.Objects;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;

/** Required credential admission hook in security-enabled compositions. */
public final class CredentialAdmission implements OperationAdmission {
    private final ObjectProvider<AuthorizationContext> authorization;

    CredentialAdmission(ObjectProvider<AuthorizationContext> authorization) {
        this.authorization = Objects.requireNonNull(authorization);
    }

    @Override public void admit() {
        try {
            authorization.getObject();
        } catch (BeanCreationException failure) {
            // Certify only the exact expected factory failure at the pre-entry boundary.
            if (failure.getCause() instanceof BeanInstantiationException instantiation
                    && instantiation.getCause() instanceof SupplementaryCredentialRejected rejected) {
                throw rejected.certifyPreEntry();
            }
            throw failure;
        }
    }
}

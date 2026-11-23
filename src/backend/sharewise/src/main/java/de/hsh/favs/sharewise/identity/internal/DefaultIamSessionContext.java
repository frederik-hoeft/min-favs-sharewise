package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.identity.internal.auth.IamSessionContext;
import de.hsh.favs.sharewise.identity.internal.auth.VerifiedIamSession;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Reads only trusted ingress-initialized primary evidence; callers cannot supply a session ID. */
@Component
@Scope("operation")
final class DefaultIamSessionContext implements IamSessionContext {

    private final IamOperationSecurityConfiguration ingress;

    DefaultIamSessionContext(IamOperationSecurityConfiguration ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @Override
    public VerifiedIamSession requireSession() {
        VerifiedJwtCredential primary = ingress.primary().orElseThrow(UnauthenticatedCallerException::new);
        try {
            String rawSession = primary.sessionId();
            UUID sessionId = UUID.fromString(rawSession);
            if (!sessionId.toString().equals(rawSession)) {
                throw new IllegalArgumentException("Noncanonical session ID");
            }
            return new VerifiedIamSession(primary.userId(), sessionId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid verified primary IAM session evidence", failure);
        }
    }
}

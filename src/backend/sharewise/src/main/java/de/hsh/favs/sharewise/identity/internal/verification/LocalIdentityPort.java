package de.hsh.favs.sharewise.identity.internal.verification;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.IdentityPort;
import de.hsh.favs.sharewise.identity.IdentityVerificationFailure;
import de.hsh.favs.sharewise.identity.VerifiedIdentity;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.UUID;

/** In-process outbound adapter; a remote implementation can replace it without changing callers. */
final class LocalIdentityPort implements IdentityPort {

    private final IdentityContext identity;
    private final IamIdentityVerificationService verifier;

    LocalIdentityPort(IdentityContext identity, IamIdentityVerificationService verifier) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public Result<VerifiedIdentity, IdentityVerificationFailure> verifyCurrent() {
        UUID userId = identity.requireUserId();
        UUID sessionId = identity.requireSessionId();
        return verifier.verify(userId, sessionId);
    }
}

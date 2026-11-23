package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** IAM semantic identity derives from the already verified operation evidence snapshot. */
@Component
@Scope("operation")
final class DefaultIdentityContext implements IdentityContext {

    private final Optional<Caller> caller;

    DefaultIdentityContext(AuthorizationContext authorization, IamCredentialVerifier verifier) {
        Objects.requireNonNull(authorization);
        JwtCredentialProfile profile = verifier.requireCodec().profile();
        Optional<Caller> selected = Optional.empty();
        for (AuthorizationCredential credential : authorization.credentialsFrom(profile.issuer(), profile.name())) {
            ClaimOrigin origin = credential.origin();
            if (selected.isPresent()) {
                throw new IllegalStateException("Ambiguous primary IAM identity");
            }
            UUID subject = parseSubject(origin);
            List<Claim> sessions = credential.claims().stream()
                .filter(claim -> "sid".equals(claim.type())).toList();
            if (sessions.size() != 1 || sessions.getFirst().value().isBlank()) {
                throw new IllegalStateException("Invalid primary IAM session evidence");
            }
            selected = Optional.of(new Caller(subject, sessions.getFirst().value()));
        }
        if (selected.isEmpty() && !authorization.credentials().isEmpty()) {
            throw new IllegalStateException("Authorization evidence without a primary IAM credential");
        }
        caller = selected;
    }

    private static UUID parseSubject(ClaimOrigin origin) {
        try {
            String subject = origin.subject().orElseThrow();
            UUID id = UUID.fromString(subject);
            if (!id.toString().equals(subject)) {
                throw new IllegalArgumentException("Noncanonical IAM identity");
            }
            return id;
        } catch (IllegalArgumentException | java.util.NoSuchElementException exception) {
            throw new IllegalStateException("Invalid primary IAM identity evidence");
        }
    }

    @Override
    public Optional<UUID> userId() {
        return caller.map(Caller::userId);
    }

    @Override
    public Optional<String> sessionId() {
        return caller.map(Caller::sessionId);
    }

    private record Caller(UUID userId, String sessionId) { }
}

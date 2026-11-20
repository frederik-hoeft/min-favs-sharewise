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

    private final Optional<UUID> userId;

    DefaultIdentityContext(AuthorizationContext authorization, IamCredentialVerifier verifier) {
        Objects.requireNonNull(authorization);
        JwtCredentialProfile profile = verifier.requireCodec().profile();
        Optional<UUID> selected = Optional.empty();
        for (AuthorizationCredential credential : authorization.credentials()) {
            ClaimOrigin origin = credential.origin();
            if (!origin.issuer().equals(profile.issuer()) || !origin.profile().equals(profile.name())) {
                continue;
            }
            if (selected.isPresent()) {
                throw new IllegalStateException("Ambiguous primary IAM identity");
            }
            UUID subject = parseSubject(origin);
            List<Claim> sessions = credential.claims().stream()
                .filter(claim -> "sid".equals(claim.type())).toList();
            if (sessions.size() != 1 || sessions.getFirst().value().isBlank()) {
                throw new IllegalStateException("Invalid primary IAM session evidence");
            }
            selected = Optional.of(subject);
        }
        if (selected.isEmpty() && !authorization.credentials().isEmpty()) {
            throw new IllegalStateException("Authorization evidence without a primary IAM credential");
        }
        userId = selected;
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
        return userId;
    }
}

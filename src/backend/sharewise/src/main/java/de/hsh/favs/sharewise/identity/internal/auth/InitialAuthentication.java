package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.session.LoginSession;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenRecord;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IssuedAccessToken;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates the initial IAM session and credentials inside the caller's transaction segment. */
public final class InitialAuthentication {

    private final SessionStore sessions;
    private final RefreshTokenStore refreshTokens;
    private final RefreshCredentialGenerator secrets;
    private final SessionPolicy policy;
    private final AccessTokenIssuer accessTokens;

    public InitialAuthentication(SessionStore sessions, RefreshTokenStore refreshTokens,
            RefreshCredentialGenerator secrets, SessionPolicy policy, AccessTokenIssuer accessTokens) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
    }

    /** This result must not escape until Transactions.execute has confirmed COMMIT. */
    AuthenticationResult create(UUID userId, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        LoginSession session = sessions.insert(userId, policy.startAt(now));
        if (!session.userId().equals(userId) || session.revokedAt() != null) {
            throw new IllegalStateException("New IAM session has inconsistent state");
        }
        UUID tokenId = refreshTokens.newTokenId();
        RefreshCredentialGenerator.GeneratedCredential credential = secrets.generate(tokenId);
        refreshTokens.insert(new RefreshTokenRecord(tokenId, session.id(), credential.verifierHash(),
                now, null));
        Instant expiry = policy.accessExpiresAt(now, session.deadlines());
        IssuedAccessToken accessToken = accessTokens.issue(userId, session.id(), now, expiry);
        if (!expiry.equals(accessToken.expiresAt())) {
            throw new IllegalStateException("Access token issuer returned the wrong expiration");
        }
        return new AuthenticationResult(userId, session.id(), accessToken, credential,
                session.deadlines().effectiveExpiresAt());
    }
}

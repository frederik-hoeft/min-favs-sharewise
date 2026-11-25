package de.hsh.favs.sharewise.identity.internal.auth.session;

import de.hsh.favs.sharewise.shared.infra.security.ApplicationToken;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistable verifier and history metadata, never a refresh bearer value. */
public final class RefreshTokenRecord {

    private final UUID id;
    private final UUID sessionId;
    private final byte[] verifierHash;
    private final Instant issuedAt;
    private final Instant consumedAt;

    public RefreshTokenRecord(UUID id, UUID sessionId, byte[] verifierHash,
            Instant issuedAt, Instant consumedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(verifierHash, "verifierHash");
        if (verifierHash.length != ApplicationToken.VERIFIER_BYTES) {
            throw new IllegalArgumentException("Invalid refresh verifier length");
        }
        this.verifierHash = verifierHash.clone();
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        if (consumedAt != null && consumedAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("Consumption cannot precede issuance");
        }
        this.consumedAt = consumedAt;
    }

    public UUID id() { return id; }
    public UUID sessionId() { return sessionId; }
    public byte[] verifierHash() { return verifierHash.clone(); }
    public Instant issuedAt() { return issuedAt; }
    public Instant consumedAt() { return consumedAt; }

    @Override
    public String toString() {
        return "RefreshTokenRecord[id=" + id + ", sessionId=" + sessionId
                + ", verifier redacted]";
    }
}

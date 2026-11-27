package de.hsh.favs.sharewise.groups.internal.authorization;

import java.time.Instant;
import java.util.Objects;

/** Signed membership assertion; never include bearer material in diagnostics. */
public record IssuedGroupAttestation(String attestation, Instant expiresAt) {
    public IssuedGroupAttestation {
        Objects.requireNonNull(attestation);
        Objects.requireNonNull(expiresAt);
    }
    @Override public String toString() { return "IssuedGroupAttestation[expiresAt=" + expiresAt + "]"; }
}

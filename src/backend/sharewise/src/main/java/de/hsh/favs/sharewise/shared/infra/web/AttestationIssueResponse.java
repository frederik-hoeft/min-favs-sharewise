package de.hsh.favs.sharewise.shared.infra.web;

import java.time.Instant;
import java.util.Objects;

/** Opaque attestation and advisory expiry metadata. Never log the credential. */
public record AttestationIssueResponse(String attestation, Instant expiresAt) {
    public AttestationIssueResponse {
        Objects.requireNonNull(attestation, "attestation");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
    @Override public String toString() { return "AttestationIssueResponse[expiresAt=" + expiresAt + "]"; }
}

package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.infra.security.ApplicationToken;
import java.util.Objects;

/** Stored verification material, never part of the public invitation projection. */
public final class StoredInvitation {
    private final InvitationSummary summary;
    private final byte[] verifier;

    public StoredInvitation(InvitationSummary summary, byte[] verifier) {
        this.summary = Objects.requireNonNull(summary, "summary");
        this.verifier = Objects.requireNonNull(verifier, "verifier").clone();
    }

    public InvitationSummary summary() {
        return summary;
    }

    boolean matches(ApplicationToken token) {
        return token.matches(summary.id(), verifier);
    }

    @Override
    public String toString() {
        return "StoredInvitation[redacted]";
    }
}

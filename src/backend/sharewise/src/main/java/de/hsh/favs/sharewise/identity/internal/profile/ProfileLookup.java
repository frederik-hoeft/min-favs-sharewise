package de.hsh.favs.sharewise.identity.internal.profile;

import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Validated raw batch, with limits applied before deduplication. */
public final class ProfileLookup {
    public static final int MAX_IDS = 100;
    private final List<UUID> userIds;
    private ProfileLookup(List<UUID> userIds) { this.userIds = List.copyOf(userIds); }
    public List<UUID> userIds() { return userIds; }
    public static Result<ProfileLookup, ValidationErrors> tryFrom(List<String> raw) {
        if (raw == null || raw.size() > MAX_IDS) return invalid();
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) return invalid();
            try {
                UUID id = UUID.fromString(value);
                if (!id.toString().equals(value)) return invalid();
                ids.add(id);
            } catch (IllegalArgumentException failure) { return invalid(); }
        }
        return Result.ok(new ProfileLookup(List.copyOf(ids)));
    }
    private static Result<ProfileLookup, ValidationErrors> invalid() {
        return Result.err(ValidationErrors.single("userIds", "invalid", "Provide up to 100 canonical user IDs."));
    }
}

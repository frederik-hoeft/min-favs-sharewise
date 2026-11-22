package de.hsh.favs.sharewise.identity.internal.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

/** Profile update payload; distinguishes an explicit null (clear) from an omitted field. */
public final class AccountProfileUpdateRequest {

    private String displayName;
    private boolean displayNameSupplied;
    private boolean displayNameTypeValid = true;
    private boolean unknownFieldSupplied;

    @JsonSetter("displayName")
    public void setDisplayName(Object value) {
        // Avoid Jackson's string coercion of numbers and booleans at the transport boundary.
        displayNameSupplied = true;
        displayNameTypeValid = value == null || value instanceof String;
        displayName = value instanceof String text ? text : null;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        unknownFieldSupplied = true;
    }

    public boolean hasValidShape() {
        return displayNameSupplied && displayNameTypeValid && !unknownFieldSupplied;
    }

    public String displayName() {
        return displayName;
    }
}

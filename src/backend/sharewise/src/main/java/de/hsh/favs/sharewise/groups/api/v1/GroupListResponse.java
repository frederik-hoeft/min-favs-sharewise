package de.hsh.favs.sharewise.groups.api.v1;

import java.util.List;

/**
 * The caller's groups. The list is wrapped so that pagination metadata can be added without
 * breaking clients.
 *
 * @param items the groups in which the caller is a member, ordered by case-insensitive name and then ID
 */
public record GroupListResponse(List<GroupSummaryResponse> items) {

    public GroupListResponse {
        items = List.copyOf(items);
    }
}

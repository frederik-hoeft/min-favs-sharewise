package de.hsh.favs.sharewise.identity.internal.web.dto;

/**
 * Cookie-backed lifecycle input; the opaque refresh secret stays in its HttpOnly cookie.
 *
 * @param expectedSessionId canonical session UUID for an established tab, or {@code null}
 *                          for the initial unbound restoration (and unbound idempotent logout)
 */
public record SessionBindingRequest(String expectedSessionId) { }

package de.hsh.favs.sharewise.groups.internal.application;

/**
 * Untrusted input for creating a group. The creator is always the authenticated caller.
 *
 * @param name the requested name, validated by the service
 * @param description the requested description, possibly {@code null}
 */
public record CreateGroupCommand(String name, String description) { }

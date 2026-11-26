/**
 * Versioned integration events published by the IAM bounded context.
 *
 * <p>Events are immutable business facts that other contexts consume asynchronously through
 * the Spring Modulith publication registry. Their fully qualified type names are persisted in
 * {@code messaging.event_publication}, so renaming or moving a type requires draining
 * outstanding publications first. Incompatible changes get a new versioned type.
 */
@NamedInterface("events")
package de.hsh.favs.sharewise.identity.events;

import org.springframework.modulith.NamedInterface;

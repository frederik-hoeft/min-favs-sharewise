/**
 * Versioned integration events published by Group Management.
 *
 * <p>Events are immutable facts about committed group creation and organizational membership
 * that other contexts consume asynchronously through the Spring Modulith publication registry.
 * Group publishes them inside the transaction segment of the mutation they describe, so the
 * fact, the Group state and the delivery obligation commit or roll back together.
 *
 * <p>Membership facts carry the group's {@code membershipRevision}, a group-local source
 * revision advanced by exactly one with every membership insertion or deletion. It is the only
 * ordering authority; {@code occurredAt} is metadata. The event type is the resulting membership
 * state of the user at that revision. Role changes are not membership changes and publish nothing.
 *
 * <p>Fully qualified type names are persisted in {@code messaging.event_publication}, so renaming
 * or moving a type requires draining outstanding publications first. Incompatible changes get a
 * new versioned type.
 */
@NamedInterface("events")
package de.hsh.favs.sharewise.groups.events;

import org.springframework.modulith.NamedInterface;

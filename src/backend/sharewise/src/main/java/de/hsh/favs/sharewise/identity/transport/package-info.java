/**
 * IAM-owned HTTP security contribution consumed by the application composition root.
 * Domain modules consume operation-scoped identity, not this transport integration hook.
 */
@NamedInterface("transport")
package de.hsh.favs.sharewise.identity.transport;

import org.springframework.modulith.NamedInterface;

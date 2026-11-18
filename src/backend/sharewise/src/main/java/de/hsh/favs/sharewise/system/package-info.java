/**
 * Host-local system HTTP endpoints for the modular monolith.
 *
 * <p>System health probes consume only the published {@code shared::health}
 * capability; business HTTP adapters belong to their owning context modules.
 */
@ApplicationModule(allowedDependencies = { "shared :: health" })
package de.hsh.favs.sharewise.system;

import org.springframework.modulith.ApplicationModule;

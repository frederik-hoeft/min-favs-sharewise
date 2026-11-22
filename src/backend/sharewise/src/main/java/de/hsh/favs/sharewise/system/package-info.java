/**
 * Host-local system HTTP endpoints for the modular monolith.
 *
 * <p>System health probes consume the published {@code shared::health} and {@code shared::web}
 * capability; business HTTP adapters belong to their owning context modules.
 */
@ApplicationModule(allowedDependencies = { "shared :: health", "shared :: web" })
package de.hsh.favs.sharewise.system;

import org.springframework.modulith.ApplicationModule;

/**
 * Group Management: groups, their metadata, organizational membership and roles.
 *
 * <p>The top-level package publishes no types yet; HTTP adapters, application services,
 * domain values and persistence adapters are internal to the module.
 */
@ApplicationModule(allowedDependencies = {"identity", "shared::result", "shared::transactions"})
package de.hsh.favs.sharewise.groups;

import org.springframework.modulith.ApplicationModule;

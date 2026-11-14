/**
 * Uses {@code shared} without declaring it; only the global
 * {@code sharedModules} configuration makes verification accept this.
 */
@ApplicationModule(allowedDependencies = {})
package de.hsh.favs.modulithfixtures.implicitshared.consumer;

import org.springframework.modulith.ApplicationModule;

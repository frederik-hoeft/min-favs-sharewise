/**
 * Business-independent infrastructure shared by the application modules.
 *
 * <p>This module depends on no other application module. Its top-level
 * package publishes nothing; each infrastructure capability is published
 * through its own named interface, such as {@code shared::di}. All other
 * sub-packages are internal to the module.
 */
@ApplicationModule(allowedDependencies = {})
package de.hsh.favs.sharewise.shared;

import org.springframework.modulith.ApplicationModule;

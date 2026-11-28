/**
 * Group Management: groups, their metadata, organizational membership and roles.
 *
 * <p>The top-level package publishes organizational roles and authorization contracts; HTTP adapters, application services,
 * domain values and persistence adapters are internal to the module.
 */
@ApplicationModule(allowedDependencies = {
    "identity", "identity::events", "shared::claims", "shared::di", "shared::jooq", "shared::result", "shared::security", "shared::transactions", "shared::validation",
    "shared::web"})
package de.hsh.favs.sharewise.groups;

import org.springframework.modulith.ApplicationModule;

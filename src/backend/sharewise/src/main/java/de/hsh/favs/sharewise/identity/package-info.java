/**
 * Identity and authentication contracts published by the IAM bounded context.
 *
 * <p>Application services consume this API without depending on credential
 * transports, persistence, or IAM implementations.
 */
@ApplicationModule(allowedDependencies = {"shared::claims", "shared::di", "shared::security", "shared::web"})
package de.hsh.favs.sharewise.identity;

import org.springframework.modulith.ApplicationModule;

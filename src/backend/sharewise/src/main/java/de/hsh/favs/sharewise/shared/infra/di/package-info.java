/**
 * Public dependency-injection scope contracts for application modules.
 *
 * <p>This package is the named interface {@code shared::di} of the
 * {@code shared} module. Modules that use these contracts declare
 * {@code "shared :: di"} in their allowed dependencies. Spring-specific
 * implementations live in the {@code internal} subpackage, which is not
 * published.
 */
@NamedInterface("di")
package de.hsh.favs.sharewise.shared.infra.di;

import org.springframework.modulith.NamedInterface;

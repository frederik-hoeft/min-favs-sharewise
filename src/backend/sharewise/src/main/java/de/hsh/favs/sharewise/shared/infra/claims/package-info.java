/**
 * Source-agnostic, read-only claim evidence contracts.
 *
 * <p>The {@code shared::claims} named interface exposes verified evidence to
 * domain-owned semantic contexts. Evidence provenance is not itself proof of
 * authorization. Spring implementations and provider composition are private
 * to the {@code internal} package.
 */
@NamedInterface("claims")
package de.hsh.favs.sharewise.shared.infra.claims;

import org.springframework.modulith.NamedInterface;

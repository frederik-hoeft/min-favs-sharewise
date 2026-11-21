/**
 * Source-agnostic, read-only authorization evidence contracts.
 *
 * <p>The {@code shared::claims} named interface exposes verified evidence to
 * domain-owned semantic contexts. Verified credentials establish evidence,
 * not permission to perform any particular business action. Spring implementations and provider composition are private
 * to the {@code internal} package.
 */
@NamedInterface("claims")
package de.hsh.favs.sharewise.shared.infra.claims;

import org.springframework.modulith.NamedInterface;

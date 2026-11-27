/**
 * Source-agnostic, read-only authorization evidence and credential admission SPI.
 *
 * <p>The {@code shared::claims} named interface exposes verified evidence to
 * domain-owned semantic contexts. Verified credentials establish evidence,
 * not permission to perform any particular business action. The application-facing evidence is format-neutral; the current provider SPI carries independently verified JWT proofs. Spring implementations and correlation are private
 * to the {@code internal} package.
 */
@NamedInterface("claims")
package de.hsh.favs.sharewise.shared.infra.claims;

import org.springframework.modulith.NamedInterface;

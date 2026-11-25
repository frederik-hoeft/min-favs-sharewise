/**
 * Trusted cryptographic primitives for JWT/JWS credentials and opaque application tokens.
 *
 * <p>The {@code shared::security} named interface provides signing, verification,
 * credential-level principal/session binding, and opaque bearer generation and parsing.
 * It does not grant permissions, select accounts or authorize business operations.
 * Owning modules configure JWT keys/profiles and own the persistence, scope and
 * lifecycle of their opaque tokens; a parsed token alone grants no authorization.
 */
@NamedInterface("security")
package de.hsh.favs.sharewise.shared.infra.security;

import org.springframework.modulith.NamedInterface;

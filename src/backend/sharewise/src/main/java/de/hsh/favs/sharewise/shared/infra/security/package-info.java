/**
 * Trusted, issuer/profile-specific JWT/JWS signing and verification primitives.
 *
 * <p>The {@code shared::security} named interface provides cryptographic verification
 * and credential-level principal/session binding. It does not grant permissions,
 * select user accounts or authorize business operations. Instances must be
 * configured with trusted keys and profiles by their owning ingress/issuer.
 */
@NamedInterface("security")
package de.hsh.favs.sharewise.shared.infra.security;

import org.springframework.modulith.NamedInterface;

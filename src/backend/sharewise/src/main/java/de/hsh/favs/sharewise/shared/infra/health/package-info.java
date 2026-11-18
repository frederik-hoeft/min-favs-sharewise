/**
 * Published readiness-probe capability for the system HTTP adapter.
 *
 * <p>Exposes only the {@link de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator}
 * contract. Concrete probes remain internal to shared infrastructure.
 */
@NamedInterface("health")
package de.hsh.favs.sharewise.shared.infra.health;

import org.springframework.modulith.NamedInterface;
